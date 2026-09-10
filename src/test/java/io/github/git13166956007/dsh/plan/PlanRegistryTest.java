package io.github.git13166956007.dsh.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanRegistryTest {
    @Test
    void approvalAndStepTransitionsArePersisted() {
        InMemoryPlanStore store = new InMemoryPlanStore();
        PlanRegistry registry = new PlanRegistry(store);
        Plan plan = registry.create("Release", "Ship the release", "default", "default", true,
                List.of(new PlanRegistry.PlanStepInput("Build", "Run the build", 2),
                        new PlanRegistry.PlanStepInput("Verify", "Check the result", 1)));

        assertEquals(PlanStatus.DRAFT, plan.status());
        registry.approve(plan.id());
        registry.start(plan.id());
        registry.startStep(plan.id(), plan.steps().get(0).id());
        registry.completeStep(plan.id(), plan.steps().get(0).id(), "build ok");

        Plan current = registry.find(plan.id());
        assertEquals(PlanStatus.RUNNING, current.status());
        assertEquals(PlanStepStatus.COMPLETED, current.steps().get(0).status());
        assertEquals("build ok", current.steps().get(0).result());
        assertEquals(1, current.steps().get(0).attempts());
    }

    @Test
    void executionRequiresApproval() {
        PlanRegistry registry = new PlanRegistry(new InMemoryPlanStore());
        Plan plan = registry.create("Draft", "Needs review", null, null, true,
                List.of(new PlanRegistry.PlanStepInput("Step", "Do it", null)));

        assertThrows(IllegalStateException.class, () -> registry.start(plan.id()));
        assertEquals(PlanStatus.DRAFT, registry.find(plan.id()).status());
    }

    @Test
    void nonApprovalPlanStartsReadyForExecution() {
        PlanRegistry registry = new PlanRegistry(new InMemoryPlanStore());
        Plan plan = registry.create("Auto", "Run it", null, null, false,
                List.of(new PlanRegistry.PlanStepInput("Step", "Do it", null)));

        assertEquals(PlanStatus.APPROVED, plan.status());
    }
}
