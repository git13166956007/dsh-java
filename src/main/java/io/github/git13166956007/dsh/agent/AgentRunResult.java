package io.github.git13166956007.dsh.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record AgentRunResult(String answer, List<AgentTraceEvent> trace, int turns, String runId,
                             PendingToolApproval pendingApproval, List<ChatMessage> conversationMessages) {
    public AgentRunResult(String answer, List<AgentTraceEvent> trace, int turns) {
        this(answer, trace, turns, null, null, List.of());
    }

    public AgentRunResult(String answer, List<AgentTraceEvent> trace, int turns, String runId) {
        this(answer, trace, turns, runId, null, List.of());
    }

    public AgentRunResult(String answer, List<AgentTraceEvent> trace, int turns, String runId,
                          PendingToolApproval pendingApproval) {
        this(answer, trace, turns, runId, pendingApproval, List.of());
    }

    public AgentRunResult {
        trace = Collections.unmodifiableList(new ArrayList<AgentTraceEvent>(trace));
        conversationMessages = Collections.unmodifiableList(new ArrayList<ChatMessage>(
                conversationMessages == null ? List.of() : conversationMessages));
    }

    /** Returns the latest model reasoning block so callers can persist the final assistant turn. */
    public String reasoningContent() {
        for (int index = trace.size() - 1; index >= 0; index--) {
            AgentTraceEvent event = trace.get(index);
            if ("model".equals(event.type())) return event.reasoningContent();
        }
        return null;
    }
}
