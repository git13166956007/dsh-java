package io.github.git13166956007.dsh.plan;

import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentMode;
import io.github.git13166956007.dsh.agent.AgentRunResult;
import io.github.git13166956007.dsh.agent.AgentStreamListener;
import io.github.git13166956007.dsh.agent.SubAgentProfile;
import io.github.git13166956007.dsh.agent.SubAgentProfileRegistry;
import io.github.git13166956007.dsh.model.ModelProfileData;
import io.github.git13166956007.dsh.model.ModelHealth;
import io.github.git13166956007.dsh.model.ModelRegistry;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.skill.SkillRegistry;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class AdaptivePlanService {
    private final AgentLoop agentLoop;
    private final PlanRegistry plans;
    private final SubAgentProfileRegistry subAgents;
    private final ObjectMapper objectMapper;
    private final ToolRegistry tools;
    private final SkillRegistry skills;
    private final RunManager runs;
    private final ModelRegistry models;

    public AdaptivePlanService(AgentLoop agentLoop, PlanRegistry plans, SubAgentProfileRegistry subAgents,
                               ObjectMapper objectMapper) {
        this(agentLoop, plans, subAgents, objectMapper, null, null);
    }

    public AdaptivePlanService(AgentLoop agentLoop, PlanRegistry plans, SubAgentProfileRegistry subAgents,
                               ObjectMapper objectMapper, ToolRegistry tools, SkillRegistry skills) {
        this(agentLoop, plans, subAgents, objectMapper, tools, skills, null, null);
    }

    public AdaptivePlanService(AgentLoop agentLoop, PlanRegistry plans, SubAgentProfileRegistry subAgents,
                               ObjectMapper objectMapper, ToolRegistry tools, SkillRegistry skills,
                               RunManager runs, ModelRegistry models) {
        this.agentLoop = agentLoop;
        this.plans = plans;
        this.subAgents = subAgents;
        this.objectMapper = objectMapper;
        this.tools = tools;
        this.skills = skills;
        this.runs = runs;
        this.models = models;
    }

    public Plan create(String prompt, String apiKey, String agentId, String modelId,
                       boolean approvalRequired, Integer maxSteps) throws Exception {
        return create(prompt, apiKey, agentId, modelId, approvalRequired, maxSteps, 1);
    }

    public Plan create(String prompt, String apiKey, String agentId, String modelId,
                       boolean approvalRequired, Integer maxSteps, Integer maxConcurrency) throws Exception {
        return create(prompt, apiKey, agentId, modelId, approvalRequired, maxSteps, maxConcurrency, false);
    }

    public Plan create(String prompt, String apiKey, String agentId, String modelId,
                       boolean approvalRequired, Integer maxSteps, Integer maxConcurrency,
                       boolean allowDynamicSubAgents) throws Exception {
        return create(prompt, apiKey, agentId, modelId, approvalRequired, maxSteps, maxConcurrency,
                allowDynamicSubAgents, null);
    }

    public Plan create(String prompt, String apiKey, String agentId, String modelId,
                       boolean approvalRequired, Integer maxSteps, Integer maxConcurrency,
                       boolean allowDynamicSubAgents, AgentStreamListener listener) throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) throw new IllegalArgumentException("prompt must not be blank");
        int stepLimit = maxSteps == null ? 8 : maxSteps;
        if (stepLimit < 1 || stepLimit > 16) throw new IllegalArgumentException("maxSteps must be between 1 and 16");

        String planningPrompt = planningPrompt(prompt, stepLimit);
        AgentRunResult result = listener == null
                ? agentLoop.runDetailed(planningPrompt, apiKey, List.of(), modelId, agentId, AgentMode.PLANNING)
                : agentLoop.runStreaming(planningPrompt, apiKey, List.of(), modelId, agentId,
                        AgentMode.PLANNING, listener);
        JsonNode planJson = parseJson(result.answer());
        String title = required(planJson.path("title").asText(null), "generated plan title");
        String goal = required(planJson.path("goal").asText(null), "generated plan goal");
        JsonNode stepNodes = planJson.path("steps");
        if (!stepNodes.isArray() || stepNodes.size() < 1 || stepNodes.size() > stepLimit) {
            throw new IllegalArgumentException("generated plan steps must contain between 1 and " + stepLimit + " items");
        }

        List<PlanRegistry.PlanStepInput> steps = new ArrayList<PlanRegistry.PlanStepInput>();
        int stepNo = 1;
        for (JsonNode step : stepNodes) {
            String stepTitle = required(step.path("title").asText(null), "generated step title");
            String instruction = required(step.path("instruction").asText(null), "generated step instruction");
            String subAgentId = chooseSubAgent(stepTitle + "\n" + instruction, step);
            if (subAgentId == null && allowDynamicSubAgents) {
                subAgentId = createDynamicSubAgent(step, stepTitle, instruction, modelId);
            }
            int maxAttempts = boundedInt(step, "maxAttempts", 1, 1, 10);
            steps.add(new PlanRegistry.PlanStepInput(stepTitle, instruction, maxAttempts, subAgentId,
                    readIntegers(step.path("dependsOn"), stepNo)));
            stepNo++;
        }
        return plans.create(title, goal, agentId, modelId, approvalRequired,
                maxConcurrency == null ? 1 : maxConcurrency, steps);
    }

    private static String planningPrompt(String prompt, int stepLimit) {
        return "Create an execution plan for the user's task. Return JSON only, with this exact shape: "
                + "{\"title\":\"short title\",\"goal\":\"goal\",\"steps\":["
                + "{\"title\":\"step title\",\"instruction\":\"complete instruction\",\"maxAttempts\":1,"
                + "\"dependsOn\":[],"
                + "\"worker\":{\"name\":\"optional worker name\",\"systemPrompt\":\"optional worker instructions\","
                + "\"modelId\":\"optional model profile id\",\"maxTurns\":8,\"maxToolCalls\":64,"
                + "\"timeoutSeconds\":300,\"maxDepth\":4,\"priority\":50,\"costWeight\":1.0,"
                + "\"maxConcurrentRuns\":4,\"capabilityTags\":[],\"allowedToolNames\":[],\"skillIds\":[]}}]}"
                + " No Markdown, no code fence, no commentary. Use at most " + stepLimit + " ordered steps.\n\nTask:\n" + prompt.trim();
    }

    private String createDynamicSubAgent(JsonNode step, String title, String instruction, String modelId) {
        JsonNode worker = step.path("worker");
        String name = requiredOrDefault(worker.path("name").asText(null), "Adaptive worker - " + title);
        String systemPrompt = requiredOrDefault(worker.path("systemPrompt").asText(null),
                "You are an adaptive execution worker. Focus on this task and report verifiable results.");
        String workerModelId = requiredOrDefault(worker.path("modelId").asText(null), modelId);
        int maxTurns = boundedInt(worker, "maxTurns", 8, 1, 64);
        int maxToolCalls = boundedInt(worker, "maxToolCalls", 64, 0, 10000);
        int timeoutSeconds = boundedInt(worker, "timeoutSeconds", 300, 0, 86400);
        int maxDepth = boundedInt(worker, "maxDepth", 4, 0, 32);
        int priority = boundedInt(worker, "priority", 50, 0, 100);
        double costWeight = boundedDouble(worker, "costWeight", 1.0, 0, 100);
        int maxConcurrentRuns = boundedInt(worker, "maxConcurrentRuns", 4, 1, 64);
        List<String> allowedTools = filterTools(readStrings(worker.path("allowedToolNames")));
        List<String> skillIds = filterSkills(readStrings(worker.path("skillIds")));
        List<String> capabilityTags = readStrings(worker.path("capabilityTags"));
        validateWorkerModel(workerModelId, allowedTools);
        return subAgents.create(name, AgentMode.EXECUTION, workerModelId, systemPrompt, maxTurns, allowedTools,
                skillIds, true, maxToolCalls, timeoutSeconds, maxDepth, priority, costWeight,
                maxConcurrentRuns, capabilityTags).id();
    }

    private List<String> filterTools(List<String> values) {
        if (tools == null) return values;
        Set<String> available = tools.list().stream().map(info -> info.name()).collect(java.util.stream.Collectors.toSet());
        return values.stream().filter(available::contains).toList();
    }

    private List<String> filterSkills(List<String> values) {
        if (skills == null) return values;
        Set<String> available = skills.list().stream().map(info -> info.id()).collect(java.util.stream.Collectors.toSet());
        return values.stream().filter(available::contains).toList();
    }

    private static List<String> readStrings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<String>();
        for (JsonNode value : node) {
            String text = value.asText(null);
            if (text != null && !text.isBlank() && !result.contains(text.trim())) result.add(text.trim());
        }
        return result;
    }

    private static List<Integer> readIntegers(JsonNode node, int stepNo) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException("generated step dependsOn must be an array");
        List<Integer> result = new ArrayList<Integer>();
        for (JsonNode value : node) {
            if (!value.isIntegralNumber()) throw new IllegalArgumentException("generated step dependencies must be integers");
            int dependency = value.asInt();
            if (dependency < 1 || dependency >= stepNo || result.contains(dependency)) {
                throw new IllegalArgumentException("generated step dependencies must reference earlier unique steps");
            }
            result.add(dependency);
        }
        return List.copyOf(result);
    }

    public List<SubAgentCandidate> rankSubAgents(String task) {
        String normalized = required(task, "task");
        Set<String> inputTokens = tokens(normalized);
        return subAgents.list().stream()
                .filter(SubAgentProfile::enabled)
                .filter(profile -> profile.mode() == AgentMode.EXECUTION)
                .map(profile -> score(profile, inputTokens, List.of(), List.of(), List.of(), null))
                .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed()
                        .thenComparing(ScoredCandidate::name))
                .map(ScoredCandidate::view)
                .toList();
    }

    private String chooseSubAgent(String text, JsonNode step) {
        Set<String> inputTokens = tokens(text);
        JsonNode worker = step == null ? null : step.path("worker");
        List<String> requestedTools = readStrings(worker == null ? null : worker.path("allowedToolNames"));
        List<String> requestedSkills = readStrings(worker == null ? null : worker.path("skillIds"));
        List<String> requestedCapabilities = readStrings(worker == null ? null : worker.path("capabilityTags"));
        String requestedModel = worker == null ? null : worker.path("modelId").asText(null);
        return subAgents.list().stream()
                .filter(SubAgentProfile::enabled)
                .filter(profile -> profile.mode() == AgentMode.EXECUTION)
                .map(profile -> score(profile, inputTokens, requestedTools, requestedSkills,
                        requestedCapabilities, requestedModel))
                .filter(candidate -> candidate.available() && candidate.semanticMatches() > 0)
                .max(Comparator.comparingInt(ScoredCandidate::score)
                        .thenComparing(ScoredCandidate::name, Comparator.reverseOrder()))
                .map(ScoredCandidate::id)
                .orElse(null);
    }

    // ponytail: deterministic scoring keeps adaptive routing inspectable; add embeddings only after measuring poor matches.
    private ScoredCandidate score(SubAgentProfile profile, Set<String> inputTokens,
                                  List<String> requestedTools, List<String> requestedSkills,
                                  List<String> requestedCapabilities, String requestedModel) {
        Set<String> profileTokens = tokens(profile.name() + " " + profile.systemPrompt() + " "
                + String.join(" ", profile.allowedToolNames()) + " " + String.join(" ", profile.skillIds())
                + " " + String.join(" ", profile.capabilityTags()));
        List<String> matchedTokens = inputTokens.stream().filter(profileTokens::contains).sorted().toList();
        int toolMatches = intersectionCount(requestedTools, profile.allowedToolNames());
        int skillMatches = intersectionCount(requestedSkills, profile.skillIds());
        int capabilityMatches = intersectionCount(requestedCapabilities, profile.capabilityTags());
        int missingTools = Math.max(0, requestedTools.size() - toolMatches);
        int missingSkills = Math.max(0, requestedSkills.size() - skillMatches);
        int activeRuns = activeLoad(profile.id());
        ModelProfileData model = modelFor(profile);
        boolean modelAvailable = models == null || model != null;
        boolean available = modelAvailable && activeRuns < profile.maxConcurrentRuns();
        Double inputPrice = model == null ? null : model.inputPricePerMillionTokens();
        Double outputPrice = model == null ? null : model.outputPricePerMillionTokens();
        boolean hasModelPrice = inputPrice != null || outputPrice != null;
        double modelPrice = (inputPrice == null ? 0 : inputPrice) + (outputPrice == null ? 0 : outputPrice);
        int modelCostPenalty = (int) Math.round(modelPrice * 5.0);
        ModelHealth health = model == null ? null : models.health(model.id());
        long attempts = health == null ? 0 : health.successCount() + health.failureCount();
        double successRate = attempts == 0 ? 0 : (double) health.successCount() / attempts;
        int healthBonus = attempts == 0 ? 0 : (int) Math.round(successRate * 20);
        int failurePenalty = attempts == 0 ? 0 : (int) Math.round((1 - successRate) * 40);
        int latencyPenalty = health == null || health.lastLatencyMs() == null
                ? 0 : (int) Math.min(25, health.lastLatencyMs() / 200);
        int unhealthyPenalty = health != null && "UNHEALTHY".equals(health.status()) ? 50 : 0;
        int semanticMatches = matchedTokens.size() + toolMatches + skillMatches + capabilityMatches;
        int score = matchedTokens.size() * 10 + toolMatches * 20 + skillMatches * 20 + capabilityMatches * 25
                + (requestedModel != null && requestedModel.equals(profile.modelId()) ? 25 : 0)
                + profile.priority() - (int) Math.round(profile.costWeight() * 5.0) - modelCostPenalty
                + healthBonus - failurePenalty - latencyPenalty - unhealthyPenalty - activeRuns * 15
                - missingTools * 100 - missingSkills * 100;
        List<String> reasons = new ArrayList<String>();
        if (!matchedTokens.isEmpty()) reasons.add("tokens=" + String.join(",", matchedTokens));
        if (toolMatches > 0) reasons.add("tools=" + toolMatches);
        if (skillMatches > 0) reasons.add("skills=" + skillMatches);
        if (capabilityMatches > 0) reasons.add("capabilities=" + capabilityMatches);
        reasons.add("priority=" + profile.priority());
        reasons.add("costWeight=" + profile.costWeight());
        if (model != null && hasModelPrice) {
            reasons.add("modelPrice=$" + formatPrice(inputPrice) + "/$" + formatPrice(outputPrice) + " per 1M");
            reasons.add("modelCostPenalty=" + modelCostPenalty);
        }
        if (health != null) {
            reasons.add("modelHealth=" + health.status());
            if (attempts > 0) reasons.add("successRate=" + formatRate(successRate));
            if (health.lastLatencyMs() != null) reasons.add("latency=" + health.lastLatencyMs() + "ms");
            if (healthBonus > 0) reasons.add("healthBonus=" + healthBonus);
            if (failurePenalty > 0) reasons.add("failurePenalty=" + failurePenalty);
            if (latencyPenalty > 0) reasons.add("latencyPenalty=" + latencyPenalty);
            if (unhealthyPenalty > 0) reasons.add("unhealthyPenalty=" + unhealthyPenalty);
        }
        if (!modelAvailable) reasons.add("model unavailable");
        reasons.add("load=" + activeRuns + "/" + profile.maxConcurrentRuns());
        if (missingTools > 0 || missingSkills > 0) reasons.add("missing requested capabilities");
        if (!available) reasons.add("concurrency limit reached");
        return new ScoredCandidate(profile.id(), profile.name(), score, semanticMatches, available,
                profile.priority(), profile.costWeight(), activeRuns, profile.maxConcurrentRuns(), inputPrice,
                outputPrice, health == null ? null : health.status(), successRate,
                health == null ? null : health.lastLatencyMs(), matchedTokens, reasons);
    }

    private ModelProfileData modelFor(SubAgentProfile profile) {
        if (models == null) return null;
        try {
            return models.resolve(profile.modelId());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return null;
        }
    }

    private static String formatPrice(Double value) {
        return value == null ? "?" : String.format(Locale.ROOT, "%.6f", value);
    }

    private static String formatRate(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private int activeLoad(String profileId) {
        if (runs == null) return subAgents.activeRunCount(profileId);
        try {
            return (int) runs.list().stream().filter(run -> profileId.equals(run.agentId()) && !run.status().terminal()).count();
        } catch (Exception exception) {
            throw new IllegalStateException("failed to inspect sub-agent load", exception);
        }
    }

    private void validateWorkerModel(String modelId, List<String> allowedTools) {
        if (models == null) return;
        ModelProfileData model = models.resolve(modelId);
        if (!allowedTools.isEmpty() && !model.supportsTools()) {
            throw new IllegalArgumentException("adaptive worker model does not support tool calling: " + model.id());
        }
    }

    private static int intersectionCount(List<String> left, List<String> right) {
        Set<String> values = new HashSet<String>(right);
        return (int) left.stream().filter(values::contains).distinct().count();
    }

    private JsonNode parseJson(String answer) throws Exception {
        if (answer == null || answer.isBlank()) throw new IllegalArgumentException("planning model returned an empty plan");
        String value = answer.trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int end = value.lastIndexOf("```");
            if (firstLine > 0 && end > firstLine) value = value.substring(firstLine + 1, end).trim();
        }
        JsonNode node = objectMapper.readTree(value);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("planning model returned invalid JSON");
        return node;
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new HashSet<String>();
        Matcher matcher = Pattern.compile("[a-z0-9_-]+|[\\p{IsHan}]+")
                .matcher(value == null ? "" : value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.codePoints().allMatch(AdaptivePlanService::isHan)) {
                if (token.length() >= 2) result.add(token);
                for (int index = 0; index + 1 < token.length(); index++) {
                    result.add(token.substring(index, index + 2));
                }
            } else if (token.length() >= 3) {
                result.add(token);
            }
        }
        return result;
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String requiredOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int boundedInt(JsonNode object, String field, int fallback, int minimum, int maximum) {
        JsonNode value = object == null ? null : object.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) return fallback;
        if (!value.isIntegralNumber()) throw new IllegalArgumentException("generated worker " + field + " must be an integer");
        int number = value.asInt();
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException("generated worker " + field + " must be between " + minimum + " and " + maximum);
        }
        return number;
    }

    private static double boundedDouble(JsonNode object, String field, double fallback, double minimum, double maximum) {
        JsonNode value = object == null ? null : object.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) return fallback;
        if (!value.isNumber()) throw new IllegalArgumentException("generated worker " + field + " must be a number");
        double number = value.asDouble();
        if (Double.isNaN(number) || Double.isInfinite(number) || number < minimum || number > maximum) {
            throw new IllegalArgumentException("generated worker " + field + " must be between " + minimum + " and " + maximum);
        }
        return number;
    }

    public record SubAgentCandidate(String id, String name, int score, int priority, double costWeight,
                                    int activeRuns, int maxConcurrentRuns, boolean available,
                                    Double inputPricePerMillionTokens, Double outputPricePerMillionTokens,
                                    String modelHealthStatus, double modelSuccessRate, Long modelLastLatencyMs,
                                    List<String> matchedTokens, List<String> reasons) {
    }

    private record ScoredCandidate(String id, String name, int score, int semanticMatches, boolean available,
                                   int priority, double costWeight, int activeRuns, int maxConcurrentRuns,
                                   Double inputPricePerMillionTokens, Double outputPricePerMillionTokens,
                                   String modelHealthStatus, double modelSuccessRate, Long modelLastLatencyMs,
                                   List<String> matchedTokens, List<String> reasons) {
        private SubAgentCandidate view() {
            return new SubAgentCandidate(id, name, score, priority, costWeight, activeRuns, maxConcurrentRuns,
                    available, inputPricePerMillionTokens, outputPricePerMillionTokens, modelHealthStatus,
                    modelSuccessRate, modelLastLatencyMs, matchedTokens, reasons);
        }
    }
}
