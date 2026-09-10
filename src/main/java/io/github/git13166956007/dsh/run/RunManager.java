package io.github.git13166956007.dsh.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;

public final class RunManager {
    private final RunStore store;
    private final Map<String, List<Consumer<RunEvent>>> listeners = new HashMap<String, List<Consumer<RunEvent>>>();

    public RunManager(RunStore store) {
        this.store = store;
    }

    public String start(RunSpec spec) throws Exception {
        String id = UUID.randomUUID().toString();
        RunData run = new RunData(id, spec.parentRunId(), spec.kind(), RunStatus.RUNNING, spec.conversationId(),
                spec.planId(), spec.stepId(), spec.agentId(), spec.modelId(), Instant.now(), null, null, null);
        store.saveRun(run);
        event(id, "run_started", spec.kind().value());
        return id;
    }

    public void complete(String id, String output) throws Exception {
        update(id, RunStatus.COMPLETED, null, output);
        event(id, "run_completed", output);
    }

    public void fail(String id, String error) throws Exception {
        update(id, RunStatus.FAILED, error, null);
        event(id, "run_failed", error);
    }

    public void cancel(String id) throws Exception {
        update(id, RunStatus.CANCELLED, null, null);
        event(id, "run_cancelled", null);
    }

    public void waitForApproval(String id, String payload) throws Exception {
        update(id, RunStatus.WAITING_APPROVAL, null, null, null);
        event(id, "tool_approval_required", payload);
    }

    public void resume(String id) throws Exception {
        update(id, RunStatus.RUNNING, null, null, null);
        event(id, "run_resumed", null);
    }

    public synchronized void event(String runId, String type, String payload) throws Exception {
        long id = store instanceof InMemoryRunStore memory ? memory.nextEventId() : 0;
        RunEventData data = store.saveEvent(new RunEventData(id, runId, type, payload, Instant.now()));
        RunEvent event = RunEvent.from(data);
        for (Consumer<RunEvent> listener : new ArrayList<Consumer<RunEvent>>(
                listeners.getOrDefault(runId, List.of()))) {
            try {
                listener.accept(event);
            } catch (Exception ignored) {
                // A disconnected stream must not break run persistence or other subscribers.
            }
        }
    }

    public synchronized AutoCloseable subscribe(String runId, Consumer<RunEvent> listener) throws Exception {
        if (find(runId) == null) throw new IllegalArgumentException("unknown run: " + runId);
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        listeners.computeIfAbsent(runId, ignored -> new ArrayList<Consumer<RunEvent>>()).add(listener);
        return () -> unsubscribe(runId, listener);
    }

    private synchronized void unsubscribe(String runId, Consumer<RunEvent> listener) {
        List<Consumer<RunEvent>> values = listeners.get(runId);
        if (values == null) return;
        values.remove(listener);
        if (values.isEmpty()) listeners.remove(runId);
    }

    public Run find(String id) throws Exception {
        return store.listRuns().stream().filter(run -> run.id().equals(id)).findFirst().map(Run::from).orElse(null);
    }

    public List<Run> list() throws Exception {
        return store.listRuns().stream().map(Run::from).toList();
    }

    public List<RunEvent> events(String runId) throws Exception {
        return store.listEvents(runId).stream().map(RunEvent::from).toList();
    }

    public int subAgentDepth(String parentRunId) throws Exception {
        int depth = 0;
        String current = parentRunId;
        Set<String> visited = new HashSet<String>();
        List<RunData> all = store.listRuns();
        while (current != null && visited.add(current)) {
            RunData run = null;
            for (RunData candidate : all) {
                if (candidate.id().equals(current)) {
                    run = candidate;
                    break;
                }
            }
            if (run == null) break;
            if (run.kind() == RunKind.SUB_AGENT) depth++;
            current = run.parentRunId();
        }
        return depth;
    }

    private void update(String id, RunStatus status, String error, String output) throws Exception {
        update(id, status, error, output, Instant.now());
    }

    private void update(String id, RunStatus status, String error, String output, Instant completedAt) throws Exception {
        RunData current = store.listRuns().stream().filter(run -> run.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown run: " + id));
        if (current.status().terminal() && current.status() != status) return;
        store.saveRun(new RunData(current.id(), current.parentRunId(), current.kind(), status, current.conversationId(),
                current.planId(), current.stepId(), current.agentId(), current.modelId(), current.startedAt(), completedAt,
                error, output));
    }
}
