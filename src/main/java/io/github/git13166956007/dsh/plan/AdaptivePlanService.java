package io.github.git13166956007.dsh.plan;

import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentMode;
import io.github.git13166956007.dsh.agent.AgentRunResult;
import io.github.git13166956007.dsh.agent.SubAgentProfile;
import io.github.git13166956007.dsh.agent.SubAgentProfileRegistry;
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

    public AdaptivePlanService(AgentLoop agentLoop, PlanRegistry plans, SubAgentProfileRegistry subAgents,
                               ObjectMapper objectMapper) {
        this(agentLoop, plans, subAgents, objectMapper, null, null);
    }

    public AdaptivePlanService(AgentLoop agentLoop, PlanRegistry plans, SubAgentProfileRegistry subAgents,
                               ObjectMapper objectMapper, ToolRegistry tools, SkillRegistry skills) {
        this.agentLoop = agentLoop;
        this.plans = plans;
        this.subAgents = subAgents;
        this.objectMapper = objectMapper;
        this.tools = tools;
        this.skills = skills;
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
        if (prompt == null || prompt.trim().isEmpty()) throw new IllegalArgumentException("prompt must not be blank");
        int stepLimit = maxSteps == null ? 8 : maxSteps;
        if (stepLimit < 1 || stepLimit > 16) throw new IllegalArgumentException("maxSteps must be between 1 and 16");

        String planningPrompt = "Create an execution plan for the user's task. Return JSON only, with this exact shape: "
                + "{\"title\":\"short title\",\"goal\":\"goal\",\"steps\":["
                + "{\"title\":\"step title\",\"instruction\":\"complete instruction\","
                + "\"dependsOn\":[],"
                + "\"worker\":{\"name\":\"optional worker name\",\"systemPrompt\":\"optional worker instructions\","
                + "\"modelId\":\"optional model profile id\",\"maxTurns\":8,\"maxToolCalls\":64,"
                + "\"timeoutSeconds\":300,\"maxDepth\":4,\"allowedToolNames\":[],\"skillIds\":[]}}]}"
                + " No Markdown, no code fence, no commentary. Use at most " + stepLimit + " ordered steps.\n\nTask:\n" + prompt.trim();
        AgentRunResult result = agentLoop.runDetailed(planningPrompt, apiKey, List.of(), modelId, agentId, AgentMode.PLANNING);
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
            String subAgentId = chooseSubAgent(stepTitle + "\n" + instruction);
            if (subAgentId == null && allowDynamicSubAgents) {
                subAgentId = createDynamicSubAgent(step, stepTitle, instruction, modelId);
            }
            steps.add(new PlanRegistry.PlanStepInput(stepTitle, instruction, 1, subAgentId,
                    readIntegers(step.path("dependsOn"), stepNo)));
            stepNo++;
        }
        return plans.create(title, goal, agentId, modelId, approvalRequired,
                maxConcurrency == null ? 1 : maxConcurrency, steps);
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
        List<String> allowedTools = filterTools(readStrings(worker.path("allowedToolNames")));
        List<String> skillIds = filterSkills(readStrings(worker.path("skillIds")));
        return subAgents.create(name, AgentMode.EXECUTION, workerModelId, systemPrompt, maxTurns, allowedTools,
                skillIds, true, maxToolCalls, timeoutSeconds, maxDepth).id();
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

    private String chooseSubAgent(String text) {
        Set<String> inputTokens = tokens(text);
        return subAgents.list().stream()
                .filter(SubAgentProfile::enabled)
                .filter(profile -> profile.mode() == AgentMode.EXECUTION)
                .map(profile -> new Candidate(profile.id(), score(profile, inputTokens)))
                .filter(candidate -> candidate.score() > 0)
                .max(Comparator.comparingInt(Candidate::score))
                .map(Candidate::id)
                .orElse(null);
    }

    // ponytail: token overlap is intentionally deterministic; replace with embeddings only when matching quality is measured as insufficient.
    private static int score(SubAgentProfile profile, Set<String> inputTokens) {
        Set<String> profileTokens = tokens(profile.name() + " " + profile.systemPrompt() + " "
                + String.join(" ", profile.allowedToolNames()) + " " + String.join(" ", profile.skillIds()));
        int score = 0;
        for (String token : inputTokens) if (profileTokens.contains(token)) score++;
        return score;
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

    private record Candidate(String id, int score) {
    }
}
