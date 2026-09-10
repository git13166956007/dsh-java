package io.github.git13166956007.dsh.agent;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** A durable run id paired with the in-process task that is executing it. */
public final class AgentRunHandle {
    private final String runId;
    private final CompletableFuture<AgentRunResult> result;
    private final BooleanSupplier cancelAction;

    AgentRunHandle(String runId, CompletableFuture<AgentRunResult> result, BooleanSupplier cancelAction) {
        this.runId = runId;
        this.result = result;
        this.cancelAction = cancelAction;
    }

    public String runId() {
        return runId;
    }

    public CompletableFuture<AgentRunResult> result() {
        return result;
    }

    public boolean cancel() {
        return cancelAction.getAsBoolean();
    }
}
