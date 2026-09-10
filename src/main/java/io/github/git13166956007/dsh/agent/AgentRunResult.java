package io.github.git13166956007.dsh.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record AgentRunResult(String answer, List<AgentTraceEvent> trace, int turns) {
    public AgentRunResult {
        trace = Collections.unmodifiableList(new ArrayList<AgentTraceEvent>(trace));
    }
}
