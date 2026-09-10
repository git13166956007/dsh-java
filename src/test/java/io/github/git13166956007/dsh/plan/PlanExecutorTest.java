package io.github.git13166956007.dsh.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.InMemorySubAgentProfileStore;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.agent.SubAgentProfileRegistry;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PlanExecutorTest {
    @Test
    void runsIndependentStepsInParallelUpToPlanLimit() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) throws Exception {
                int current = active.incrementAndGet();
                peak.accumulateAndGet(current, Math::max);
                Thread.sleep(100);
                active.decrementAndGet();
                return new ModelResponse("ok", List.of(), "stop");
            }
        };
        PlanRegistry registry = new PlanRegistry(new InMemoryPlanStore());
        Plan plan = registry.create("Parallel", "Run two", null, null, false, 2,
                List.of(new PlanRegistry.PlanStepInput("A", "A", 1),
                        new PlanRegistry.PlanStepInput("B", "B", 1)));
        try (PlanExecutor executor = new PlanExecutor(registry, new AgentLoop(model, new ToolRegistry(), 1),
                new io.github.git13166956007.dsh.agent.SubAgentRunner(
                        new AgentLoop(model, new ToolRegistry(), 1),
                        new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 1)))) {
            executor.execute(plan.id(), null);
            long deadline = System.currentTimeMillis() + 3000;
            Plan current;
            do {
                Thread.sleep(30);
                current = registry.find(plan.id());
            } while (!current.status().terminal() && System.currentTimeMillis() < deadline);

            assertEquals(PlanStatus.COMPLETED, current.status());
            assertTrue(peak.get() >= 2);
        }
    }
}
