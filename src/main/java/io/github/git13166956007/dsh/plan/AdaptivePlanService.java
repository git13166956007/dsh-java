package io.github.git13166956007.dsh.plan;

import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentMode;
import io.github.git13166956007.dsh.agent.AgentRunResult;
import io.github.git13166956007.dsh.agent.SubAgentProfile;
import io.github.git13166956007.dsh.agent.SubAgentProfileRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class AdaptivePlanService {
    private final AgentLoop agentLoop;
    private final PlanRegistry plans;
    private final SubAgentProfileRegistry subAgents;
    private final ObjectMapper objectMapper;

    public AdaptivePlanService(AgentLoop agentLoop, PlanRegistry plans, SubAgentProfileRegistry subAgents,
                               ObjectMapper objectMapper) {
        this.agentLoop = agentLoop;
        this.plans = plans;
        this.subAgents = subAgents;
        this.objectMapper = objectMapper;
    }

    public Plan create(String prompt, String apiKey, String agentId, String modelId,
                       boolean approvalRequired, Integer maxSteps) throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) throw new IllegalArgumentException("prompt must not be blank");
        int stepLimit = maxSteps == null ? 8 : maxSteps;
        if (stepLimit < 1 || stepLimit > 16) throw new IllegalArgumentException("maxSteps must be between 1 and 16");

        String planningPrompt = "Create an execution plan for the user's task. Return JSON only, with this exact shape: "
                + "{\"title\":\"short title\",\"goal\":\"goal\",\"steps\":["
                + "{\"title\":\"step title\",\"instruction\":\"complete instruction\"}]}"
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
        for (JsonNode step : stepNodes) {
            String stepTitle = required(step.path("title").asText(null), "generated step title");
            String instruction = required(step.path("instruction").asText(null), "generated step instruction");
            steps.add(new PlanRegistry.PlanStepInput(stepTitle, instruction, 1, chooseSubAgent(stepTitle + "\n" + instruction)));
        }
        return plans.create(title, goal, agentId, modelId, approvalRequired, steps);
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
        for (String token : value.toLowerCase(Locale.ROOT).split("[^a-z0-9_-]+")) {
            if (token.length() >= 3) result.add(token);
        }
        return result;
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record Candidate(String id, int score) {
    }
}
