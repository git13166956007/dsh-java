package io.github.git13166956007.dsh.agent;

public final class SubAgentApprovalRequiredException extends IllegalStateException {
    private final AgentRunResult pendingResult;

    public SubAgentApprovalRequiredException(AgentRunResult pendingResult) {
        super("sub-agent tool approval required");
        this.pendingResult = pendingResult;
    }

    public AgentRunResult pendingResult() {
        return pendingResult;
    }
}
