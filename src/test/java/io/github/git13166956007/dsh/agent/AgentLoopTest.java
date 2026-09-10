package io.github.git13166956007.dsh.agent;

import tools.jackson.databind.node.JsonNodeFactory;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.run.InMemoryRunStore;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.run.RunStatus;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentLoopTest {
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
}
