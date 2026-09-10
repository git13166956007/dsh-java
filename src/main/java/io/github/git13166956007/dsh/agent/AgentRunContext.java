package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.run.RunKind;

public record AgentRunContext(
        String parentRunId,
        RunKind kind,
        String conversationId,
        String planId,
        String stepId,
        String agentId) {
    public AgentRunContext {
        if (kind == null) kind = RunKind.AGENT;
    }

    public static AgentRunContext standalone() {
        return new AgentRunContext(null, RunKind.AGENT, null, null, null, null);
    }

    public static AgentRunContext chat(String conversationId, String agentId) {
        return new AgentRunContext(null, RunKind.CHAT, conversationId, null, null, agentId);
    }

    public static AgentRunContext child(String parentRunId, RunKind kind, String conversationId,
                                        String planId, String stepId, String agentId) {
        return new AgentRunContext(parentRunId, kind, conversationId, planId, stepId, agentId);
    }
}
