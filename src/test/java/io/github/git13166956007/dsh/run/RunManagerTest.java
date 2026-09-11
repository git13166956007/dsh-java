package io.github.git13166956007.dsh.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RunManagerTest {
    @Test
    void storesLifecycleAndEventsWithParentRelationship() throws Exception {
        RunManager manager = new RunManager(new InMemoryRunStore());
        String parent = manager.start(new RunSpec(null, RunKind.PLAN, null, "plan-1", null, null, null));
        String child = manager.start(new RunSpec(parent, RunKind.PLAN_STEP, null, "plan-1", "step-1", null, null));
        manager.event(child, "tool_call", "time_now");
        manager.complete(child, "done");
        manager.complete(parent, "completed");

        Run saved = manager.find(child);
        assertNotNull(saved);
        assertEquals(parent, saved.parentRunId());
        assertEquals(RunStatus.COMPLETED, saved.status());
        assertEquals(List.of("run_started", "tool_call", "run_completed"),
                manager.events(child).stream().map(RunEvent::type).toList());
    }

    @Test
    void broadcastsNewEventsToSubscribersAndStopsAfterClose() throws Exception {
        RunManager manager = new RunManager(new InMemoryRunStore());
        String runId = manager.start(new RunSpec(null, RunKind.AGENT, null, null, null, null, null));
        List<String> types = new ArrayList<String>();
        AutoCloseable subscription = manager.subscribe(runId, event -> types.add(event.type()));

        manager.event(runId, "plan_step_started", "step-1");
        assertEquals(List.of("plan_step_started"), types);
        subscription.close();
        manager.event(runId, "plan_step_completed", "step-1");
        assertEquals(List.of("plan_step_started"), types);
    }

    @Test
    void terminalRunCannotBeReopenedByACompetingLifecycleCommand() throws Exception {
        RunManager manager = new RunManager(new InMemoryRunStore());
        String runId = manager.start(new RunSpec(null, RunKind.AGENT, null, null, null, null, null));

        manager.complete(runId, "done");
        manager.cancel(runId);
        manager.fail(runId, "late failure");

        Run saved = manager.find(runId);
        assertNotNull(saved);
        assertEquals(RunStatus.COMPLETED, saved.status());
        assertEquals("done", saved.output());
        assertEquals(List.of("run_started", "run_completed"),
                manager.events(runId).stream().map(RunEvent::type).toList());
    }

    @Test
    void runEventKeysAreIdempotentAndRejectConflictingRetries() throws Exception {
        RunManager manager = new RunManager(new InMemoryRunStore());
        String runId = manager.start(RunSpec.standalone());
        java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
        manager.subscribe(runId, ignored -> notifications.incrementAndGet());

        manager.event(runId, "tool:1", "tool_call", "weather");
        manager.event(runId, "tool:1", "tool_call", "weather");

        assertEquals(2, manager.events(runId).size());
        assertEquals(1, notifications.get());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> manager.event(runId, "tool:1", "tool_call", "different"));
    }

    @Test
    void atomicTransitionDoesNotAdvanceStateWhenItsEventConflicts() throws Exception {
        InMemoryRunStore store = new InMemoryRunStore();
        RunData current = new RunData("run-atomic", null, RunKind.AGENT, RunStatus.RUNNING, null, null, null,
                null, null, java.time.Instant.now(), null, null, null);
        store.saveRun(current);
        store.saveEvent(new RunEventData(1, current.id(), "transition", "existing", "payload", java.time.Instant.now()));
        RunData next = new RunData(current.id(), current.parentRunId(), current.kind(), RunStatus.COMPLETED,
                current.conversationId(), current.planId(), current.stepId(), current.agentId(), current.modelId(),
                current.startedAt(), java.time.Instant.now(), null, "done");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.compareAndSetStatusAndEvent(current, next,
                        new RunEventData(2, current.id(), "transition", "different", "payload", java.time.Instant.now())));
        assertEquals(RunStatus.RUNNING, store.listRuns().get(0).status());
        assertEquals(1, store.listEvents(current.id()).size());
    }

    @Test
    void slowListenerDoesNotHoldTheRunManagerLock() throws Exception {
        RunManager manager = new RunManager(new InMemoryRunStore());
        String runId = manager.start(new RunSpec(null, RunKind.AGENT, null, null, null, null, null));
        String otherRunId = manager.start(new RunSpec(null, RunKind.AGENT, null, null, null, null, null));
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AtomicBoolean firstNotification = new AtomicBoolean(true);
        manager.subscribe(runId, event -> {
            if (!firstNotification.compareAndSet(true, false)) return;
            listenerStarted.countDown();
            try {
                releaseListener.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> slow = CompletableFuture.runAsync(() -> {
                try {
                    manager.event(runId, "slow", null);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }, executor);
            assertEquals(true, listenerStarted.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> fast = CompletableFuture.runAsync(() -> {
                try {
                    manager.event(otherRunId, "fast", null);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }, executor);
            fast.get(2, TimeUnit.SECONDS);
            releaseListener.countDown();
            slow.get(2, TimeUnit.SECONDS);
            assertEquals(List.of("run_started", "slow"), manager.events(runId).stream()
                    .map(RunEvent::type).toList());
            assertEquals(List.of("run_started", "fast"), manager.events(otherRunId).stream()
                    .map(RunEvent::type).toList());
        } finally {
            releaseListener.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void listenerCanAppendAReentrantEventWithoutDeadlocking() throws Exception {
        RunManager manager = new RunManager(new InMemoryRunStore());
        String runId = manager.start(new RunSpec(null, RunKind.AGENT, null, null, null, null, null));
        AtomicBoolean appended = new AtomicBoolean();
        manager.subscribe(runId, event -> {
            if ("outer".equals(event.type()) && appended.compareAndSet(false, true)) {
                try {
                    manager.event(runId, "inner", null);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        });

        manager.event(runId, "outer", null);
        assertEquals(List.of("run_started", "outer", "inner"), manager.events(runId).stream()
                .map(RunEvent::type).toList());
    }
}
