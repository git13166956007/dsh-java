package io.github.git13166956007.dsh.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.ArrayList;
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
}
