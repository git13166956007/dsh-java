package io.github.git13166956007.dsh.agent;

import java.util.List;

public record SubAgentProfileData(
        String id,
        String name,
        AgentMode mode,
        String modelId,
        String systemPrompt,
        int maxTurns,
        List<String> allowedToolNames,
        List<String> skillIds,
        boolean enabled,
        int maxToolCalls,
        int timeoutSeconds,
        int maxDepth,
        int priority,
        double costWeight,
        int maxConcurrentRuns,
        List<String> capabilityTags) {
    public SubAgentProfileData(String id, String name, AgentMode mode, String modelId, String systemPrompt,
                               int maxTurns, List<String> allowedToolNames, List<String> skillIds, boolean enabled,
                               int maxToolCalls, int timeoutSeconds, int maxDepth) {
        this(id, name, mode, modelId, systemPrompt, maxTurns, allowedToolNames, skillIds, enabled,
                maxToolCalls, timeoutSeconds, maxDepth, 50, 1.0, 4, List.of());
    }

    public SubAgentProfileData(String id, String name, AgentMode mode, String modelId, String systemPrompt,
                               int maxTurns, List<String> allowedToolNames, List<String> skillIds, boolean enabled) {
        this(id, name, mode, modelId, systemPrompt, maxTurns, allowedToolNames, skillIds, enabled,
                64, 300, 4, 50, 1.0, 4, List.of());
    }
}
