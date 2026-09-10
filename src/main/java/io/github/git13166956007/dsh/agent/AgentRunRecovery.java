package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.run.Run;
import io.github.git13166956007.dsh.run.RunKind;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.run.RunStatus;
import java.util.HashSet;
import java.util.Set;

/** Reattaches durable asynchronous Sub-agent runs after an application restart. */
public final class AgentRunRecovery {
    private final AgentContinuationStore continuations;
    private final RunManager runs;
    private final AgentLoop agentLoop;
    private final SubAgentProfileRegistry profiles;
    private final SubAgentSessionManager sessions;

    public AgentRunRecovery(AgentContinuationStore continuations, RunManager runs, AgentLoop agentLoop,
                            SubAgentProfileRegistry profiles,
                            SubAgentSessionManager sessions) {
        this.continuations = continuations;
        this.runs = runs;
        this.agentLoop = agentLoop;
        this.profiles = profiles;
        this.sessions = sessions;
    }

    public void recover() {
        try {
            Set<String> persistedRequests = new HashSet<String>(continuations.listRunIds());
            for (Run run : runs.list()) {
                if (run.status() != RunStatus.RUNNING || run.kind() != RunKind.SUB_AGENT) continue;
                try {
                    if (!persistedRequests.contains(run.id())) {
                        fail(run, "run was interrupted by application restart and has no recovery request");
                        continue;
                    }
                    AgentLoop.AsyncRequest request = agentLoop.readAsyncRequest(continuations.load(run.id()));
                    if (request == null || request.prompt() == null || request.prompt().isBlank()) {
                        fail(run, "run was interrupted by application restart and has invalid recovery data");
                        continue;
                    }
                    if (run.agentId() == null || run.agentId().isBlank()) {
                        fail(run, "run was interrupted by application restart and has no sub-agent profile");
                        continue;
                    }
                    profiles.resolve(run.agentId());
                    sessions.trackRecoveredRun(run);
                    AgentExecutionOptions options = new AgentExecutionOptions(request.options().modelId(),
                            request.options().mode(), request.options().systemPrompt(), request.options().maxTurns(),
                            request.options().allowedToolNames(), request.options().skillIds(),
                            request.options().maxToolCalls(), request.options().timeoutSeconds(), request.options().maxDepth());
                    AgentRunHandle handle = agentLoop.resumeAsync(run.id(), request.prompt(), null, request.history(),
                            options, AgentRunContext.child(run.parentRunId(), run.kind(), run.conversationId(),
                                    run.planId(), run.stepId(), run.agentId()));
                    handle.result().whenComplete((result, error) -> {
                        try { sessions.onRunResult(handle.runId(), result, error); }
                        catch (Exception ignored) { }
                    });
                } catch (Exception exception) {
                    fail(run, "run recovery failed: " + exception.getMessage());
                }
            }
        } catch (Exception exception) {
            // Startup must remain available even when one persisted run cannot be inspected.
        }
    }

    private void fail(Run run, String message) {
        try { runs.fail(run.id(), message); }
        catch (Exception ignored) { }
    }
}
