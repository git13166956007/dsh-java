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
import java.util.concurrent.CompletableFuture;

public final class RunManager {
    private final RunStore store;
    private final Map<String, List<Consumer<RunEvent>>> listeners = new HashMap<String, List<Consumer<RunEvent>>>();
    private final Map<String, CompletableFuture<Void>> notificationTails = new HashMap<String, CompletableFuture<Void>>();
    private final ThreadLocal<String> notifyingRun = new ThreadLocal<String>();

    public RunManager(RunStore store) {
        this.store = store;
    }

    public String start(RunSpec spec) throws Exception {
        String id;
        synchronized (this) {
            id = UUID.randomUUID().toString();
            RunData run = new RunData(id, spec.parentRunId(), spec.kind(), RunStatus.RUNNING, spec.conversationId(),
                    spec.planId(), spec.stepId(), spec.agentId(), spec.modelId(), Instant.now(), null, null, null);
            store.saveRun(run);
        }
        event(id, "lifecycle:started", "run_started", spec.kind().value());
        return id;
    }

    public void complete(String id, String output) throws Exception {
        transition(id, RunStatus.COMPLETED, null, output, "run_completed", output);
    }

    public void fail(String id, String error) throws Exception {
        transition(id, RunStatus.FAILED, error, null, "run_failed", error);
    }

    public void cancel(String id) throws Exception {
        transition(id, RunStatus.CANCELLED, null, null, "run_cancelled", null);
    }

    public void waitForApproval(String id, String payload) throws Exception {
        transition(id, RunStatus.WAITING_APPROVAL, null, null, "tool_approval_required", payload);
    }

    public void resume(String id) throws Exception {
        transition(id, RunStatus.RUNNING, null, null, "run_resumed", null);
    }

    public void event(String runId, String type, String payload) throws Exception {
        event(runId, null, type, payload);
    }

    /** Persists a run event; a non-blank event key makes retries idempotent within the run. */
    public void event(String runId, String eventKey, String type, String payload) throws Exception {
        RunEvent event;
        synchronized (this) {
            long id = store instanceof InMemoryRunStore memory ? memory.nextEventId() : 0;
            RunEventData data = store.saveEvent(new RunEventData(id, runId, eventKey, type, payload, Instant.now()));
            event = RunEvent.from(data);
        }
        notifyEvent(event);
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

    public synchronized Run find(String id) throws Exception {
        return store.listRuns().stream().filter(run -> run.id().equals(id)).findFirst().map(Run::from).orElse(null);
    }

    public synchronized List<Run> list() throws Exception {
        return store.listRuns().stream().map(Run::from).toList();
    }

    public synchronized List<RunEvent> events(String runId) throws Exception {
        return store.listEvents(runId).stream().map(RunEvent::from).toList();
    }

    public synchronized int subAgentDepth(String parentRunId) throws Exception {
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

    private synchronized boolean transition(String id, RunStatus status, String error, String output,
                                             String eventType, String payload) throws Exception {
        RunData current = store.listRuns().stream().filter(run -> run.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown run: " + id));
        if (current.status() == status || current.status().terminal()) return false;
        RunData next = new RunData(current.id(), current.parentRunId(), current.kind(), status, current.conversationId(),
                current.planId(), current.stepId(), current.agentId(), current.modelId(), current.startedAt(),
                status.terminal() ? Instant.now() : null, error, output);
        String eventKey = "lifecycle:" + eventType + ":" + store.listEvents(id).size();
        long eventId = store instanceof InMemoryRunStore memory ? memory.nextEventId() : 0;
        RunEventData saved = store.compareAndSetStatusAndEvent(current, next,
                new RunEventData(eventId, id, eventKey, eventType, payload, Instant.now()));
        if (saved == null) {
            RunData observed = store.listRuns().stream().filter(run -> run.id().equals(id)).findFirst().orElse(null);
            if (observed != null && (observed.status() == status || observed.status().terminal())) return false;
            throw new IllegalStateException("concurrent run state update rejected: " + id);
        }
        notifyEvent(RunEvent.from(saved));
        return true;
    }

    private void notifyEvent(RunEvent event) {
        List<Consumer<RunEvent>> listenersSnapshot;
        CompletableFuture<Void> previousNotification;
        CompletableFuture<Void> currentNotification = new CompletableFuture<Void>();
        synchronized (this) {
            listenersSnapshot = new ArrayList<Consumer<RunEvent>>(
                    listeners.getOrDefault(event.runId(), List.of()));
            previousNotification = notificationTails.getOrDefault(event.runId(), CompletableFuture.completedFuture(null));
            notificationTails.put(event.runId(), currentNotification);
        }
        if (!event.runId().equals(notifyingRun.get())) previousNotification.join();
        String previousRun = notifyingRun.get();
        notifyingRun.set(event.runId());
        try {
            for (Consumer<RunEvent> listener : listenersSnapshot) {
                try { listener.accept(event); }
                catch (Exception ignored) {
                    // A disconnected stream must not break run persistence or other subscribers.
                }
            }
        } finally {
            if (previousRun == null) notifyingRun.remove();
            else notifyingRun.set(previousRun);
            currentNotification.complete(null);
            synchronized (this) {
                if (notificationTails.get(event.runId()) == currentNotification) notificationTails.remove(event.runId());
            }
        }
    }
}
