package io.github.git13166956007.dsh.agent;

public record AgentProfile(
        String id,
        String name,
        AgentMode mode,
        String modelId,
        String systemPrompt,
        int maxTurns,
        boolean enabled,
        boolean active) {
    public static AgentProfile from(AgentProfileData data) {
        return new AgentProfile(data.id(), data.name(), data.mode(), data.modelId(), data.systemPrompt(),
                data.maxTurns(), data.enabled(), data.active());
    }
}
