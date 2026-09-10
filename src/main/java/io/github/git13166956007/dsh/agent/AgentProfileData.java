package io.github.git13166956007.dsh.agent;

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
        boolean active) {
    public AgentProfileData(String id, String name, AgentMode mode, String modelId, String systemPrompt,
                            int maxTurns, boolean enabled, boolean active) {
        this(id, name, mode, modelId, systemPrompt, maxTurns, 64, 300, 4, enabled, active);
    }
}
