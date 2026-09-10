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
        boolean enabled) {
}
