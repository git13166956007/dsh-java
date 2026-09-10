package io.github.git13166956007.dsh.agent;

public record AgentProfileData(
        String id,
        String name,
        AgentMode mode,
        String modelId,
        String systemPrompt,
        int maxTurns,
        boolean enabled,
        boolean active) {
}
