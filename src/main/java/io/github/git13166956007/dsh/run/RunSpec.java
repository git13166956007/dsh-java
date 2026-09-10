package io.github.git13166956007.dsh.run;

public record RunSpec(
        String parentRunId,
        RunKind kind,
        String conversationId,
        String planId,
        String stepId,
        String agentId,
        String modelId) {
    public RunSpec {
        if (kind == null) kind = RunKind.AGENT;
    }

    public static RunSpec standalone() {
        return new RunSpec(null, RunKind.AGENT, null, null, null, null, null);
    }
}
