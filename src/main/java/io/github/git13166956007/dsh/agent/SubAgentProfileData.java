package io.github.git13166956007.dsh.agent;

import java.util.List;
import java.util.Map;

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
        List<String> capabilityTags,
        Map<String, String> permissions) {
    public SubAgentProfileData(String id, String name, AgentMode mode, String modelId, String systemPrompt,
                               int maxTurns, List<String> allowedToolNames, List<String> skillIds, boolean enabled,
                               int maxToolCalls, int timeoutSeconds, int maxDepth) {
        this(id, name, mode, modelId, systemPrompt, maxTurns, allowedToolNames, skillIds, enabled,
                maxToolCalls, timeoutSeconds, maxDepth, 50, 1.0, 4, List.of(), Map.of());
    }

    public SubAgentProfileData(String id, String name, AgentMode mode, String modelId, String systemPrompt,
                               int maxTurns, List<String> allowedToolNames, List<String> skillIds, boolean enabled) {
        this(id, name, mode, modelId, systemPrompt, maxTurns, allowedToolNames, skillIds, enabled,
                64, 300, 4, 50, 1.0, 4, List.of(), Map.of());
    }

    public SubAgentProfileData(String id, String name, AgentMode mode, String modelId, String systemPrompt,
                               int maxTurns, List<String> allowedToolNames, List<String> skillIds, boolean enabled,
                               int maxToolCalls, int timeoutSeconds, int maxDepth, int priority, double costWeight,
                               int maxConcurrentRuns, List<String> capabilityTags) {
        this(id, name, mode, modelId, systemPrompt, maxTurns, allowedToolNames, skillIds, enabled, maxToolCalls,
                timeoutSeconds, maxDepth, priority, costWeight, maxConcurrentRuns, capabilityTags, Map.of());
    }

    public SubAgentProfileData {
        allowedToolNames = allowedToolNames == null ? List.of() : List.copyOf(allowedToolNames);
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
        permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
    }
}
