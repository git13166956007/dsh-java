package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.skill.SkillRegistry;
import java.util.ArrayList;
import java.util.List;

public final class AgentLoop {
    private final ChatModel model;
    private final ToolRegistry tools;
    private final SkillRegistry skills;
    private final int maxTurns;

    public AgentLoop(ChatModel model, ToolRegistry tools, int maxTurns) {
        this(model, tools, null, maxTurns);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, SkillRegistry skills, int maxTurns) {
        if (maxTurns < 1) throw new IllegalArgumentException("maxTurns must be positive");
        this.model = model;
        this.tools = tools;
        this.skills = skills;
        this.maxTurns = maxTurns;
    }

    public String run(String prompt) throws Exception {
        return runDetailed(prompt).answer();
    }

    public AgentRunResult runDetailed(String prompt) throws Exception {
        return runDetailed(prompt, null);
    }

    public AgentRunResult runDetailed(String prompt, String apiKey) throws Exception {
        return runDetailed(prompt, apiKey, List.of());
    }

    public AgentRunResult runDetailed(String prompt, String apiKey, List<ChatMessage> history) throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        List<AgentTraceEvent> trace = new ArrayList<AgentTraceEvent>();
        messages.add(ChatMessage.system(systemPrompt()));
        messages.addAll(history);
        messages.add(ChatMessage.user(prompt));

        for (int turn = 0; turn < maxTurns; turn++) {
            ModelResponse response = model.complete(messages, tools.definitions(), apiKey);
            messages.add(ChatMessage.assistant(response.content(), response.toolCalls()));
            if (response.content() != null && !response.content().isEmpty()) {
                trace.add(AgentTraceEvent.model(response.content()));
            }
            if (response.toolCalls().isEmpty()) {
                return new AgentRunResult(response.content() == null ? "" : response.content(), trace, turn + 1);
            }

            for (ToolCall call : response.toolCalls()) {
                String result;
                try {
                    result = tools.execute(call.name(), call.arguments());
                } catch (Exception exception) {
                    result = "Tool execution failed: " + exception.getMessage();
                }
                trace.add(AgentTraceEvent.tool(call.name(), call.arguments(), result));
                messages.add(ChatMessage.tool(call.id(), result));
            }
        }

        throw new IllegalStateException("agent exceeded max turns: " + maxTurns);
    }

    public AgentRunResult runStreaming(String prompt, String apiKey, AgentStreamListener listener) throws Exception {
        return runStreaming(prompt, apiKey, List.of(), listener);
    }

    public AgentRunResult runStreaming(String prompt, String apiKey, List<ChatMessage> history,
                                       AgentStreamListener listener) throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        List<AgentTraceEvent> trace = new ArrayList<AgentTraceEvent>();
        messages.add(ChatMessage.system(systemPrompt()));
        messages.addAll(history);
        messages.add(ChatMessage.user(prompt));

        for (int turn = 0; turn < maxTurns; turn++) {
            ModelResponse response = model.stream(messages, tools.definitions(), apiKey, listener::onText);
            messages.add(ChatMessage.assistant(response.content(), response.toolCalls()));
            if (response.content() != null && !response.content().isEmpty()) {
                trace.add(AgentTraceEvent.model(response.content()));
            }
            if (response.toolCalls().isEmpty()) {
                return new AgentRunResult(response.content() == null ? "" : response.content(), trace, turn + 1);
            }

            for (ToolCall call : response.toolCalls()) {
                listener.onToolCall(call);
                String result;
                try {
                    result = tools.execute(call.name(), call.arguments());
                } catch (Exception exception) {
                    result = "Tool execution failed: " + exception.getMessage();
                }
                AgentTraceEvent event = AgentTraceEvent.tool(call.name(), call.arguments(), result);
                trace.add(event);
                listener.onToolResult(event);
                messages.add(ChatMessage.tool(call.id(), result));
            }
        }

        throw new IllegalStateException("agent exceeded max turns: " + maxTurns);
    }

    private String systemPrompt() {
        return skills == null ? "You are a helpful assistant. Use available tools when they are useful, then give a concise final answer." : skills.systemPrompt();
    }
}
