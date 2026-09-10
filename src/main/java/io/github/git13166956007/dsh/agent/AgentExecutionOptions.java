package io.github.git13166956007.dsh.agent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record AgentExecutionOptions(
        String modelId,
        AgentMode mode,
        String systemPrompt,
        int maxTurns,
        Set<String> allowedToolNames,
        Set<String> skillIds,
        int maxToolCalls,
        int timeoutSeconds,
        int maxDepth) {
    public AgentExecutionOptions(String modelId, AgentMode mode, String systemPrompt, int maxTurns,
                                 Set<String> allowedToolNames, Set<String> skillIds) {
        this(modelId, mode, systemPrompt, maxTurns, allowedToolNames, skillIds, 64, 300, 4);
    }

    public AgentExecutionOptions {
        if (mode == null) mode = AgentMode.CHAT;
        if (maxTurns < 1) throw new IllegalArgumentException("maxTurns must be positive");
        if (maxToolCalls < 0 || maxToolCalls > 10000) throw new IllegalArgumentException("maxToolCalls must be between 0 and 10000");
        if (timeoutSeconds < 0 || timeoutSeconds > 86400) throw new IllegalArgumentException("timeoutSeconds must be between 0 and 86400");
        if (maxDepth < 0 || maxDepth > 32) throw new IllegalArgumentException("maxDepth must be between 0 and 32");
        allowedToolNames = allowedToolNames == null ? null
                : Collections.unmodifiableSet(new LinkedHashSet<String>(allowedToolNames));
        skillIds = skillIds == null ? null : Collections.unmodifiableSet(new LinkedHashSet<String>(skillIds));
    }
}
