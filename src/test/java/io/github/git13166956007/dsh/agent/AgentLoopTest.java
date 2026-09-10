package io.github.git13166956007.dsh.agent;

import tools.jackson.databind.node.JsonNodeFactory;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.run.InMemoryRunStore;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.run.RunStatus;
import io.github.git13166956007.dsh.run.RunStore;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {
    @Test
    void runsSubAgentAsynchronouslyAndCanCancelTheLiveModelCall() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        ChatModel model = (messages, definitions) -> {
            started.countDown();
            Thread.sleep(10_000);
            return new ModelResponse("late result", List.of(), "stop");
        };
        RunManager runs = new RunManager(new InMemoryRunStore());
        AgentLoop loop = new AgentLoop(model, new ToolRegistry(), null, null, null, runs, null,
                new ObjectMapper(), 2);
        try {
            AgentRunHandle handle = loop.runAsync("background task", null, List.of(),
                    new AgentExecutionOptions(null, AgentMode.EXECUTION, "", 2, null, null, 4, 300, 4),
                    AgentRunContext.child(null, io.github.git13166956007.dsh.run.RunKind.SUB_AGENT,
                            null, null, null, "worker"));

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(RunStatus.RUNNING, runs.find(handle.runId()).status());
            assertTrue(handle.cancel());
            assertEquals(RunStatus.CANCELLED, runs.find(handle.runId()).status());

            Thread.sleep(100);
            assertEquals(RunStatus.CANCELLED, runs.find(handle.runId()).status());
        } finally {
            loop.close();
        }
    }

    @Test
    void executesToolThenReturnsFinalAnswer() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        AtomicInteger executions = new AtomicInteger();
        tools.register(new ToolDefinition("demo_echo", "Echo a value.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> {
            executions.incrementAndGet();
            return "tool-result";
        });

        ChatModel model = new ChatModel() {
            private int calls;

            @Override
            public ModelResponse complete(List<ChatMessage> messages,
                                          List<ToolDefinition> definitions) {
                calls++;
                if (calls == 1) {
                    return new ModelResponse(null,
                            Collections.singletonList(new ToolCall(
                                    "call-1", "demo_echo", JsonNodeFactory.instance.objectNode())),
                            "tool_calls");
                }
                return new ModelResponse("done", Collections.emptyList(), "stop");
            }
        };

        assertEquals("done", new AgentLoop(model, tools, 2).run("hello"));
        assertEquals(1, executions.get());
    }

    @Test
    void delegatesToExecutionSubAgentAndReturnsItsResultToParent() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        SubAgentProfileRegistry profiles = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);
        SubAgentProfile worker = profiles.create("Research worker", AgentMode.EXECUTION, null, "", 2,
                List.of(), List.of(), true);
        RunManager runs = new RunManager(new InMemoryRunStore());
        ChatModel model = new ChatModel() {
            private int calls;

            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                calls++;
                if (calls == 1) {
                    assertTrue(definitions.stream().anyMatch(definition ->
                            "delegate_to_subagent".equals(definition.name())));
                    return new ModelResponse(null, List.of(new ToolCall("delegate-1", "delegate_to_subagent",
                            new ObjectMapper().createObjectNode().put("profileId", worker.id())
                                    .put("task", "delegate task"))), "tool_calls");
                }
                if (messages.get(messages.size() - 1).role() == ChatMessage.Role.USER) {
                    return new ModelResponse("child answer", List.of(), "stop");
                }
                return new ModelResponse("parent answer", List.of(), "stop");
            }
        };

        AgentLoop loop = new AgentLoop(model, tools, null, null, null, runs, null,
                new ObjectMapper(), 4);
        loop.setSubAgentRunner(new SubAgentRunner(loop, profiles));

        AgentRunResult result = loop.runDetailed("parent task", null, List.of());

        assertEquals("parent answer", result.answer());
        assertTrue(result.trace().stream().anyMatch(event ->
                "delegate_to_subagent".equals(event.name()) && event.result().contains("child answer")));
        assertEquals(1, runs.list().stream().filter(run -> run.kind() == io.github.git13166956007.dsh.run.RunKind.SUB_AGENT).count());
        assertTrue(runs.events(result.runId()).stream().anyMatch(event ->
                "sub_agent_completed".equals(event.type())));
    }

    @Test
    void propagatesSubAgentApprovalBackToTheParentRun() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        AtomicInteger executions = new AtomicInteger();
        tools.register(new ToolDefinition("child_approval_tool", "Approval tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> {
            executions.incrementAndGet();
            return "child tool result";
        });
        tools.setApprovalRequired("child_approval_tool", true);
        SubAgentProfileRegistry profiles = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);
        SubAgentProfile worker = profiles.create("Approval worker", AgentMode.EXECUTION, null, "", 3,
                List.of("child_approval_tool"), List.of(), true);
        RunManager runs = new RunManager(new InMemoryRunStore());
        InMemoryAgentContinuationStore continuations = new InMemoryAgentContinuationStore();
        ChatModel model = new ChatModel() {
            private int calls;

            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                switch (calls++) {
                    case 0:
                        return new ModelResponse(null, List.of(new ToolCall("delegate-approval", "delegate_to_subagent",
                                new ObjectMapper().createObjectNode().put("profileId", worker.id())
                                        .put("task", "run approval task"))), "tool_calls");
                    case 1:
                        return new ModelResponse(null, List.of(new ToolCall("child-approval", "child_approval_tool",
                                JsonNodeFactory.instance.objectNode())), "tool_calls");
                    case 2:
                        return new ModelResponse("child completed", List.of(), "stop");
                    default:
                        return new ModelResponse("parent completed", List.of(), "stop");
                }
            }
        };
        AgentLoop loop = new AgentLoop(model, tools, null, null, null, runs, continuations,
                new ObjectMapper(), 4);
        loop.setSubAgentRunner(new SubAgentRunner(loop, profiles));

        AgentRunResult pending = loop.runDetailed("parent task", null, List.of());

        assertNotNull(pending.pendingApproval());
        assertNotNull(pending.pendingApproval().delegatedRunId());
        assertEquals(RunStatus.WAITING_APPROVAL, runs.find(pending.runId()).status());
        assertEquals(RunStatus.WAITING_APPROVAL, runs.find(pending.pendingApproval().delegatedRunId()).status());

        AgentRunResult result = loop.resumeApproval(pending.runId(), true);

        assertEquals("parent completed", result.answer());
        assertEquals(1, executions.get());
        assertEquals(RunStatus.COMPLETED, runs.find(pending.runId()).status());
        assertEquals(RunStatus.COMPLETED, runs.find(result.runId()).status());
    }

    @Test
    void streamsDelegationThroughTheNormalToolLifecycle() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        SubAgentProfileRegistry profiles = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);
        SubAgentProfile worker = profiles.create("Streaming worker", AgentMode.EXECUTION, null, "", 2,
                List.of(), List.of(), true);
        ChatModel model = new ChatModel() {
            private int completeCalls;
            private int streamCalls;

            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                completeCalls++;
                return new ModelResponse("child streamed result", List.of(), "stop");
            }

            @Override
            public ModelResponse stream(List<ChatMessage> messages, List<ToolDefinition> definitions,
                                        String apiKey, ModelStreamListener listener) {
                streamCalls++;
                if (streamCalls == 1) {
                    return new ModelResponse(null, List.of(new ToolCall("delegate-stream", "delegate_to_subagent",
                            new ObjectMapper().createObjectNode().put("profileId", worker.id())
                                    .put("task", "stream task"))), "tool_calls");
                }
                listener.onText("parent streamed answer");
                return new ModelResponse("parent streamed answer", List.of(), "stop");
            }
        };
        AgentLoop loop = new AgentLoop(model, tools, null, null, null, null, null,
                new ObjectMapper(), 4);
        loop.setSubAgentRunner(new SubAgentRunner(loop, profiles));
        List<String> events = new ArrayList<>();

        AgentRunResult result = loop.runStreaming("parent task", null, List.of(),
                new AgentStreamListener() {
                    @Override
                    public void onText(String delta) {
                        events.add("text:" + delta);
                    }

                    @Override
                    public void onToolCall(ToolCall call) {
                        events.add("call:" + call.name());
                    }

                    @Override
                    public void onToolResult(AgentTraceEvent event) {
                        events.add("result:" + event.result());
                    }
                });

        assertEquals("parent streamed answer", result.answer());
        assertEquals(List.of("call:delegate_to_subagent", "result:Sub-agent " + worker.id()
                + " completed:\nchild streamed result", "text:parent streamed answer"), events);
    }

    @Test
    void rejectsDelegationWhenParentSubAgentDepthLimitIsZero() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        SubAgentProfileRegistry profiles = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);
        SubAgentProfile worker = profiles.create("Depth limited", AgentMode.EXECUTION, null, "", 2,
                List.of(), List.of(), true, 64, 300, 4);
        RunManager runs = new RunManager(new InMemoryRunStore());
        ChatModel model = new ChatModel() {
            private int calls;

            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                if (calls++ == 0) {
                    return new ModelResponse(null, List.of(new ToolCall("delegate-depth", "delegate_to_subagent",
                            new ObjectMapper().createObjectNode().put("profileId", worker.id())
                                    .put("task", "should be rejected"))), "tool_calls");
                }
                return new ModelResponse("parent recovered", List.of(), "stop");
            }
        };
        AgentLoop loop = new AgentLoop(model, tools, null, null, null, runs, null,
                new ObjectMapper(), 4);
        loop.setSubAgentRunner(new SubAgentRunner(loop, profiles));

        AgentRunResult result = loop.runDetailed("parent task", null, List.of(),
                new AgentExecutionOptions(null, AgentMode.CHAT, "", 4, null, null, 64, 300, 0));

        assertEquals("parent recovered", result.answer());
        assertEquals(0, runs.list().stream().filter(run -> run.kind() == io.github.git13166956007.dsh.run.RunKind.SUB_AGENT).count());
        assertTrue(runs.events(result.runId()).stream().anyMatch(event ->
                "sub_agent_failed".equals(event.type())));
    }

    @Test
    void streamsTextAndToolLifecycleEvents() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new ToolDefinition("demo_echo", "Echo a value.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> "tool-result");
        List<String> text = new ArrayList<>();
        List<String> events = new ArrayList<>();

        ChatModel model = new ChatModel() {
            private int calls;

            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                throw new UnsupportedOperationException("streaming test model");
            }

            @Override
            public ModelResponse stream(List<ChatMessage> messages, List<ToolDefinition> definitions,
                                        String apiKey, ModelStreamListener listener) {
                calls++;
                if (calls == 1) {
                    return new ModelResponse(null,
                            Collections.singletonList(new ToolCall(
                                    "call-1", "demo_echo", JsonNodeFactory.instance.objectNode())),
                            "tool_calls");
                }
                listener.onText("done");
                return new ModelResponse("done", Collections.emptyList(), "stop");
            }
        };

        AgentRunResult result = new AgentLoop(model, tools, 2).runStreaming("hello", null,
                new AgentStreamListener() {
                    @Override
                    public void onText(String delta) {
                        text.add(delta);
                    }

                    @Override
                    public void onToolCall(ToolCall call) {
                        events.add("call:" + call.name());
                    }

                    @Override
                    public void onToolResult(AgentTraceEvent event) {
                        events.add("result:" + event.result());
                    }
                });

        assertEquals("done", result.answer());
        assertEquals(Collections.singletonList("done"), text);
        assertEquals(List.of("call:demo_echo", "result:tool-result"), events);
    }

    @Test
    void planningModeDoesNotExposeToolsToTheModel() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new ToolDefinition("demo_echo", "Echo a value.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> "tool-result");
        List<Integer> definitionCounts = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                definitionCounts.add(definitions.size());
                return new ModelResponse("1. Plan\n2. Verify", List.of(), "stop");
            }
        };

        AgentProfileRegistry profiles = new AgentProfileRegistry(new InMemoryAgentProfileStore(), 8);
        profiles.update("default", null, AgentMode.PLANNING, null, null, null, null, null);

        AgentRunResult result = new AgentLoop(model, tools, null, profiles, 8)
                .runDetailed("plan this", null, List.of(), null, null, null);

        assertEquals("1. Plan\n2. Verify", result.answer());
        assertEquals(List.of(0), definitionCounts);
    }

    @Test
    void subAgentCapabilityListFiltersDefinitionsAndExecution() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        AtomicInteger executions = new AtomicInteger();
        tools.register(new ToolDefinition("allowed_tool", "Allowed tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> {
            executions.incrementAndGet();
            return "allowed";
        });
        tools.register(new ToolDefinition("blocked_tool", "Blocked tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> "blocked");

        ChatModel model = new ChatModel() {
            private int calls;

            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                assertEquals(List.of("allowed_tool"), definitions.stream().map(ToolDefinition::name).toList());
                calls++;
                if (calls == 1) return new ModelResponse(null,
                        List.of(new ToolCall("call-1", "allowed_tool", JsonNodeFactory.instance.objectNode())),
                        "tool_calls");
                return new ModelResponse("finished", List.of(), "stop");
            }
        };
        SubAgentProfileRegistry profiles = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);
        SubAgentProfile profile = profiles.create("Worker", AgentMode.EXECUTION, null, "", 2,
                List.of("allowed_tool"), List.of(), true);

        AgentRunResult result = new SubAgentRunner(new AgentLoop(model, tools, 2), profiles)
                .run("do work", null, profile.id());

        assertEquals("finished", result.answer());
        assertEquals(1, executions.get());
        assertThrows(IllegalStateException.class, () -> tools.execute("blocked_tool", JsonNodeFactory.instance.objectNode(),
                java.util.Set.of("allowed_tool")));
    }

    @Test
    void approvalPausesRunAndResumeContinuesModel() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        AtomicInteger executions = new AtomicInteger();
        tools.register(new ToolDefinition("approval_echo", "Approval tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> {
            executions.incrementAndGet();
            return "approved-result";
        });
        tools.setApprovalRequired("approval_echo", true);

        ChatModel model = new ChatModel() {
            private int calls;

            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                calls++;
                if (calls == 1) {
                    return new ModelResponse(null, List.of(new ToolCall("approval-1", "approval_echo",
                            JsonNodeFactory.instance.objectNode())), "tool_calls");
                }
                return new ModelResponse("finished after approval", List.of(), "stop");
            }
        };
        RunManager runs = new RunManager(new InMemoryRunStore());
        AgentLoop loop = new AgentLoop(model, tools, null, null, null, runs, 2);

        AgentRunResult pending = loop.runDetailed("approve this", null, List.of());
        assertNotNull(pending.pendingApproval());
        assertEquals(RunStatus.WAITING_APPROVAL, runs.find(pending.runId()).status());
        assertEquals(0, executions.get());

        AgentRunResult result = loop.resumeApproval(pending.runId(), true);
        assertEquals("finished after approval", result.answer());
        assertEquals(1, executions.get());
        assertEquals(RunStatus.COMPLETED, runs.find(pending.runId()).status());
    }

    @Test
    void restoresApprovalContinuationAfterAgentRestart() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        AtomicInteger executions = new AtomicInteger();
        tools.register(new ToolDefinition("restart_approval", "Approval tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> {
            executions.incrementAndGet();
            return "approved";
        });
        tools.setApprovalRequired("restart_approval", true);
        RunStore runStore = new InMemoryRunStore();
        RunManager runs = new RunManager(runStore);
        InMemoryAgentContinuationStore continuations = new InMemoryAgentContinuationStore();

        ChatModel firstModel = new ChatModel() {
            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                return new ModelResponse(null, List.of(new ToolCall("restart-call", "restart_approval",
                        JsonNodeFactory.instance.objectNode())), "tool_calls");
            }
        };
        AgentLoop firstLoop = new AgentLoop(firstModel, tools, null, null, null, runs,
                continuations, new ObjectMapper(), 2);
        AgentRunResult pending = firstLoop.runDetailed("restart", null, List.of());
        assertNotNull(continuations.load(pending.runId()));

        ChatModel restartedModel = new ChatModel() {
            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                return new ModelResponse("resumed", List.of(), "stop");
            }
        };
        AgentLoop restartedLoop = new AgentLoop(restartedModel, tools, null, null, null, runs,
                continuations, new ObjectMapper(), 2);

        AgentRunResult result = restartedLoop.resumeApproval(pending.runId(), true);

        assertEquals("resumed", result.answer());
        assertEquals(1, executions.get());
        assertEquals(RunStatus.COMPLETED, runs.find(pending.runId()).status());
        assertEquals(null, continuations.load(pending.runId()));
    }

    @Test
    void approvalResumeCanUseAOneRequestApiKeyWithoutPersistingIt() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new ToolDefinition("debug_key_tool", "Approval tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> "approved");
        tools.setApprovalRequired("debug_key_tool", true);
        InMemoryAgentContinuationStore continuations = new InMemoryAgentContinuationStore();
        AtomicInteger calls = new AtomicInteger();
        List<String> receivedKeys = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                return complete(messages, definitions, null, null);
            }

            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions,
                                          String apiKey, String modelId) {
                receivedKeys.add(apiKey);
                if (calls.getAndIncrement() == 0) {
                    return new ModelResponse(null, List.of(new ToolCall("debug-key-call", "debug_key_tool",
                            JsonNodeFactory.instance.objectNode())), "tool_calls");
                }
                return new ModelResponse("resumed", List.of(), "stop");
            }
        };
        AgentLoop loop = new AgentLoop(model, tools, null, null, null, null, continuations,
                new ObjectMapper(), 2);

        AgentRunResult pending = loop.runDetailed("debug key", null, List.of());
        assertFalse(continuations.load(pending.runId()).contains("temporary-debug-key"));

        AgentRunResult result = loop.resumeApproval(pending.runId(), true, "temporary-debug-key");

        assertEquals("resumed", result.answer());
        assertEquals(java.util.Arrays.asList(null, "temporary-debug-key"), receivedKeys);
        assertEquals(null, continuations.load(pending.runId()));
    }

    @Test
    void persistsRunAndToolEvents() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new ToolDefinition("demo_echo", "Echo a value.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> "echoed");
        ChatModel model = new ChatModel() {
            private int calls;

            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                calls++;
                if (calls == 1) return new ModelResponse(null,
                        List.of(new ToolCall("call-1", "demo_echo", JsonNodeFactory.instance.objectNode())),
                        "tool_calls");
                return new ModelResponse("finished", List.of(), "stop");
            }
        };
        RunManager runs = new RunManager(new InMemoryRunStore());
        AgentRunResult result = new AgentLoop(model, tools, null, null, null, runs, 2)
                .runDetailed("hello");

        assertNotNull(result.runId());
        assertEquals(RunStatus.COMPLETED, runs.find(result.runId()).status());
        assertEquals(List.of("run_started", "model_response", "tool_call", "tool_result", "model_response",
                        "run_completed"),
                runs.events(result.runId()).stream().map(io.github.git13166956007.dsh.run.RunEvent::type).toList());
    }

    @Test
    void enforcesToolCallBudget() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new ToolDefinition("budget_tool", "Budget tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> "ok");
        ChatModel model = new ChatModel() {
            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                return new ModelResponse(null, List.of(new ToolCall("budget-1", "budget_tool",
                        JsonNodeFactory.instance.objectNode())), "tool_calls");
            }
        };
        AgentExecutionOptions options = new AgentExecutionOptions(null, AgentMode.EXECUTION, "", 2,
                null, null, 0, 300, 4);
        assertThrows(AgentBudgetExceededException.class,
                () -> new AgentLoop(model, tools, 2).runDetailed("loop", null, List.of(), options));
    }

    @Test
    void enforcesSubAgentDepth() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        ChatModel model = new ChatModel() {
            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                return new ModelResponse("done", List.of(), "stop");
            }
        };
        RunManager runs = new RunManager(new InMemoryRunStore());
        String parent = runs.start(new io.github.git13166956007.dsh.run.RunSpec(null,
                io.github.git13166956007.dsh.run.RunKind.SUB_AGENT, null, null, null, "parent", null));
        AgentExecutionOptions options = new AgentExecutionOptions(null, AgentMode.EXECUTION, "", 2,
                null, null, 4, 300, 1);
        assertThrows(AgentBudgetExceededException.class, () -> new AgentLoop(model, tools, null, null, null, runs, 2)
                .runDetailed("nested", null, List.of(), options,
                        AgentRunContext.child(parent, io.github.git13166956007.dsh.run.RunKind.SUB_AGENT,
                                null, null, null, "child")));
    }
}
