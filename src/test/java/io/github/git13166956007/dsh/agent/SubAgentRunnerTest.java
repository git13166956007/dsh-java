package io.github.git13166956007.dsh.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import io.github.git13166956007.dsh.run.InMemoryRunStore;
import io.github.git13166956007.dsh.run.RunKind;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.run.RunStatus;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import io.github.git13166956007.dsh.tool.ToolRegistry;

class SubAgentRunnerTest {
    @Test
    void startsExecutionAndPersistsCompletedRun() throws Exception {
        RunManager runs = new RunManager(new InMemoryRunStore());
        SubAgentProfileRegistry profiles = profiles();
        AgentLoop loop = AgentLoop.compatibility((messages, definitions) -> new ModelResponse("completed", List.of(), "stop"),
                new ToolRegistry(), null, null, null, runs, null, null, 2);
        try {
            AgentRunHandle handle = new SubAgentRunner(loop, profiles)
                    .startForExecution("background task", null, profiles.list().get(0).id());

            assertEquals("completed", handle.result().get(2, TimeUnit.SECONDS).answer());
            assertEquals(RunKind.SUB_AGENT, runs.find(handle.runId()).kind());
            assertEquals(RunStatus.COMPLETED, runs.find(handle.runId()).status());
        } finally {
            loop.close();
        }
    }

    @Test
    void startsExecutionAndLeavesRunWaitingForApproval() throws Exception {
        RunManager runs = new RunManager(new InMemoryRunStore());
        SubAgentProfileRegistry profiles = profiles();
        ToolRegistry tools = new ToolRegistry();
        tools.register(new ToolDefinition("approval_tool", "Needs approval.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> "approved");
        tools.setApprovalRequired("approval_tool", true);
        AgentLoop loop = AgentLoop.compatibility((messages, definitions) -> new ModelResponse(null,
                List.of(new ToolCall("approval-call", "approval_tool", JsonNodeFactory.instance.objectNode())),
                "tool_calls"), tools, null, null, null, runs, new InMemoryAgentContinuationStore(), null, 2);
        try {
            SubAgentProfile profile = profiles.update(profiles.list().get(0).id(), null, null, null, null, null,
                    List.of("approval_tool"), null, null);
            AgentRunHandle handle = new SubAgentRunner(loop, profiles)
                    .startForExecution("approval task", null, profile.id());

            AgentRunResult result = handle.result().get(2, TimeUnit.SECONDS);
            assertNotNull(result.pendingApproval());
        } finally {
            loop.close();
        }
        assertEquals(RunStatus.WAITING_APPROVAL, runs.list().get(0).status());
    }

    @Test
    void cancelsExecutionAndDoesNotAllowLateModelResultToCompleteIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        RunManager runs = new RunManager(new InMemoryRunStore());
        SubAgentProfileRegistry profiles = profiles();
        AgentLoop loop = AgentLoop.compatibility((messages, definitions) -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException exception) {
                interrupted.incrementAndGet();
                throw exception;
            }
            return new ModelResponse("late result", List.of(), "stop");
        }, new ToolRegistry(), null, null, null, runs, null, null, 2);
        try {
            AgentRunHandle handle = new SubAgentRunner(loop, profiles)
                    .startForExecution("cancel task", null, profiles.list().get(0).id());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(handle.cancel());
            assertEquals(RunStatus.CANCELLED, runs.find(handle.runId()).status());
            Thread.sleep(100);
            assertEquals(RunStatus.CANCELLED, runs.find(handle.runId()).status());
            assertEquals(1, interrupted.get());
        } finally {
            loop.close();
        }
    }

    private static SubAgentProfileRegistry profiles() {
        SubAgentProfileRegistry profiles = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 2);
        profiles.create("Worker", AgentMode.EXECUTION, null, "", 2, List.of(), List.of(), true);
        return profiles;
    }
}
