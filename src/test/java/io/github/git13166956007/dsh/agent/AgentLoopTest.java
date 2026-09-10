package io.github.git13166956007.dsh.agent;

import tools.jackson.databind.node.JsonNodeFactory;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLoopTest {
    @Test
    void executesToolThenReturnsFinalAnswer() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        AtomicInteger executions = new AtomicInteger();
        tools.register(new ToolDefinition("demo.echo", "Echo a value.",
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
                                    "call-1", "demo.echo", JsonNodeFactory.instance.objectNode())),
                            "tool_calls");
                }
                return new ModelResponse("done", Collections.emptyList(), "stop");
            }
        };

        assertEquals("done", new AgentLoop(model, tools, 2).run("hello"));
        assertEquals(1, executions.get());
    }
}
