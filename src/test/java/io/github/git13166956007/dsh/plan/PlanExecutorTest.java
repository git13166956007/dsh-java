package io.github.git13166956007.dsh.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.InMemoryAgentContinuationStore;
import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.InMemorySubAgentProfileStore;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.agent.SubAgentProfileRegistry;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.run.InMemoryRunStore;
import io.github.git13166956007.dsh.run.Run;
import io.github.git13166956007.dsh.run.RunKind;
import io.github.git13166956007.dsh.run.RunManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.ObjectMapper;

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

    @Test
    void recordsPlanStepAndAgentParentRuns() throws Exception {
        ChatModel model = new ChatModel() {
            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                return new ModelResponse("ok", List.of(), "stop");
            }
        };
        PlanRegistry registry = new PlanRegistry(new InMemoryPlanStore());
        Plan plan = registry.create("Trace", "Run one", null, null, false, 1,
                List.of(new PlanRegistry.PlanStepInput("A", "A", 1)));
        RunManager runs = new RunManager(new InMemoryRunStore());
        AgentLoop tracedAgent = new AgentLoop(model, new ToolRegistry(), null, null, null, runs, 1);
        try (PlanExecutor executor = new PlanExecutor(registry, tracedAgent,
                new io.github.git13166956007.dsh.agent.SubAgentRunner(
                        new AgentLoop(model, new ToolRegistry(), 1),
                        new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 1)), runs)) {
            executor.execute(plan.id(), null);
            long deadline = System.currentTimeMillis() + 3000;
            while (!registry.find(plan.id()).status().terminal() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        }

        List<Run> saved = runs.list();
        assertEquals(3, saved.size());
        Run planRun = saved.stream().filter(run -> run.kind() == RunKind.PLAN).findFirst().orElseThrow();
        Run stepRun = saved.stream().filter(run -> run.kind() == RunKind.PLAN_STEP).findFirst().orElseThrow();
        Run agentRun = saved.stream().filter(run -> run.kind() == RunKind.AGENT).findFirst().orElseThrow();
        assertEquals(planRun.id(), stepRun.parentRunId());
        assertEquals(stepRun.id(), agentRun.parentRunId());
    }

    @Test
    void pausesPlanForToolApprovalAndResumesTheStep() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry();
        tools.register(new ToolDefinition("plan_approval_tool", "Approval tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> {
            executions.incrementAndGet();
            return "tool ok";
        });
        tools.setApprovalRequired("plan_approval_tool", true);
        ChatModel model = new ChatModel() {
            private int calls;

            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                calls++;
                if (calls == 1) return new ModelResponse(null,
                        List.of(new io.github.git13166956007.dsh.agent.ToolCall("plan-call", "plan_approval_tool",
                                JsonNodeFactory.instance.objectNode())), "tool_calls");
                return new ModelResponse("step completed", List.of(), "stop");
            }
        };
        PlanRegistry registry = new PlanRegistry(new InMemoryPlanStore());
        Plan plan = registry.create("Approval plan", "Run one approved step", null, null, false, 1,
                List.of(new PlanRegistry.PlanStepInput("Approved step", "Use the approval tool", 1)));
        RunManager runs = new RunManager(new InMemoryRunStore());
        InMemoryAgentContinuationStore continuations = new InMemoryAgentContinuationStore();
        AgentLoop agent = new AgentLoop(model, tools, null, null, null, runs, continuations, new ObjectMapper(), 2);
        Run waiting;
        try (PlanExecutor executor = new PlanExecutor(registry, agent,
                new io.github.git13166956007.dsh.agent.SubAgentRunner(agent,
                        new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 2)), runs)) {
            executor.execute(plan.id(), null);
            long deadline = System.currentTimeMillis() + 3000;
            do {
                Thread.sleep(20);
                waiting = runs.list().stream().filter(run -> run.kind() == RunKind.AGENT).findFirst().orElse(null);
            } while ((waiting == null || waiting.status() != io.github.git13166956007.dsh.run.RunStatus.WAITING_APPROVAL)
                    && System.currentTimeMillis() < deadline);

            assertEquals(PlanStatus.WAITING_APPROVAL, registry.find(plan.id()).status());
            assertEquals(0, executions.get());
        }

        AgentLoop restartedAgent = new AgentLoop(model, tools, null, null, null, runs,
                continuations, new ObjectMapper(), 2);
        try (PlanExecutor restartedExecutor = new PlanExecutor(registry, restartedAgent,
                new io.github.git13166956007.dsh.agent.SubAgentRunner(restartedAgent,
                        new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 2)), runs)) {
            restartedExecutor.resumeApproval(waiting.id(), true);
            long deadline = System.currentTimeMillis() + 3000;
            do {
                Thread.sleep(20);
            } while (!registry.find(plan.id()).status().terminal() && System.currentTimeMillis() < deadline);
        }
        assertEquals(PlanStatus.COMPLETED, registry.find(plan.id()).status());
        assertEquals(1, executions.get());
    }
}
