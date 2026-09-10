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
        int maxDepth) {
    public SubAgentProfileData(String id, String name, AgentMode mode, String modelId, String systemPrompt,
                               int maxTurns, List<String> allowedToolNames, List<String> skillIds, boolean enabled) {
        this(id, name, mode, modelId, systemPrompt, maxTurns, allowedToolNames, skillIds, enabled, 64, 300, 4);
    }
}
