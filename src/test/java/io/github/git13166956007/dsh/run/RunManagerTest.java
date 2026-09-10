package io.github.git13166956007.dsh.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
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
}
