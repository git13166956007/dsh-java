package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;

public final class AgentLoop {
    private static final String SYSTEM_PROMPT =
            "You are a helpful assistant. Use available tools when they are useful, " +
            "then give a concise final answer.";

    private final ChatModel model;
    private final ToolRegistry tools;
    private final int maxTurns;

    public AgentLoop(ChatModel model, ToolRegistry tools, int maxTurns) {
        if (maxTurns < 1) throw new IllegalArgumentException("maxTurns must be positive");
        this.model = model;
        this.tools = tools;
        this.maxTurns = maxTurns;
    }

    public String run(String prompt) throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(SYSTEM_PROMPT));
        messages.add(ChatMessage.user(prompt));

        for (int turn = 0; turn < maxTurns; turn++) {
            ModelResponse response = model.complete(messages, tools.definitions());
            messages.add(ChatMessage.assistant(response.content(), response.toolCalls()));
            if (response.toolCalls().isEmpty()) return response.content() == null ? "" : response.content();

            for (ToolCall call : response.toolCalls()) {
                String result;
                try {
                    result = tools.execute(call.name(), call.arguments());
                } catch (Exception exception) {
                    result = "Tool execution failed: " + exception.getMessage();
                }
                messages.add(ChatMessage.tool(call.id(), result));
            }
        }

        throw new IllegalStateException("agent exceeded max turns: " + maxTurns);
    }
}
