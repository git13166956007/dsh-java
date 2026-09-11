package io.github.git13166956007.dsh.agent;

import java.util.List;
import java.util.Map;

public record AgentProfileData(
        String id,
        String name,
        AgentMode mode,
        String modelId,
        String systemPrompt,
        int maxTurns,
        int maxToolCalls,
        int timeoutSeconds,
        int maxDepth,
        boolean enabled,
        boolean active,
        List<String> allowedToolNames,
        List<String> skillIds,
        Map<String, String> permissions) {
    public AgentProfileData(String id, String name, AgentMode mode, String modelId, String systemPrompt,
                            int maxTurns, boolean enabled, boolean active) {
        this(id, name, mode, modelId, systemPrompt, maxTurns, 64, 300, 4, enabled, active,
                List.of(), List.of(), Map.of());
    }

    public AgentProfileData(String id, String name, AgentMode mode, String modelId, String systemPrompt,
                            int maxTurns, int maxToolCalls, int timeoutSeconds, int maxDepth,
                            boolean enabled, boolean active) {
        this(id, name, mode, modelId, systemPrompt, maxTurns, maxToolCalls, timeoutSeconds, maxDepth,
                enabled, active, List.of(), List.of(), Map.of());
    }

    public AgentProfileData {
        allowedToolNames = allowedToolNames == null ? List.of() : List.copyOf(allowedToolNames);
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
    }
}
