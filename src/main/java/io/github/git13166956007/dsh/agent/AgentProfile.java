package io.github.git13166956007.dsh.agent;

import java.util.List;
import java.util.Map;

public record AgentProfile(
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
    public AgentProfile(String id, String name, AgentMode mode, String modelId, String systemPrompt,
                        int maxTurns, boolean enabled, boolean active) {
        this(id, name, mode, modelId, systemPrompt, maxTurns, 64, 300, 4, enabled, active,
                List.of(), List.of(), Map.of());
    }

    public AgentProfile(String id, String name, AgentMode mode, String modelId, String systemPrompt,
                        int maxTurns, int maxToolCalls, int timeoutSeconds, int maxDepth,
                        boolean enabled, boolean active) {
        this(id, name, mode, modelId, systemPrompt, maxTurns, maxToolCalls, timeoutSeconds, maxDepth,
                enabled, active, List.of(), List.of(), Map.of());
    }

    public static AgentProfile from(AgentProfileData data) {
        return new AgentProfile(data.id(), data.name(), data.mode(), data.modelId(), data.systemPrompt(),
                data.maxTurns(), data.maxToolCalls(), data.timeoutSeconds(), data.maxDepth(),
                data.enabled(), data.active(), data.allowedToolNames(), data.skillIds(), data.permissions());
    }
}
