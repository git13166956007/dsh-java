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
        Set<String> skillIds) {
    public AgentExecutionOptions {
        if (mode == null) mode = AgentMode.CHAT;
        if (maxTurns < 1) throw new IllegalArgumentException("maxTurns must be positive");
        allowedToolNames = allowedToolNames == null ? null
                : Collections.unmodifiableSet(new LinkedHashSet<String>(allowedToolNames));
        skillIds = skillIds == null ? null : Collections.unmodifiableSet(new LinkedHashSet<String>(skillIds));
    }
}
