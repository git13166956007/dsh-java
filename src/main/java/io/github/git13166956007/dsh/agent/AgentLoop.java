package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.skill.SkillRegistry;
import java.util.ArrayList;
import java.util.List;

public final class AgentLoop {
    private final ChatModel model;
    private final ToolRegistry tools;
    private final SkillRegistry skills;
    private final AgentProfileRegistry profiles;
    private final int maxTurns;

    public AgentLoop(ChatModel model, ToolRegistry tools, int maxTurns) {
        this(model, tools, null, null, maxTurns);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, SkillRegistry skills, int maxTurns) {
        this(model, tools, skills, null, maxTurns);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, SkillRegistry skills,
                     AgentProfileRegistry profiles, int maxTurns) {
        if (maxTurns < 1) throw new IllegalArgumentException("maxTurns must be positive");
        this.model = model;
        this.tools = tools;
        this.skills = skills;
        this.profiles = profiles;
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
        return runDetailed(prompt, apiKey, history, (String) null);
    }

    public AgentRunResult runDetailed(String prompt, String apiKey, List<ChatMessage> history,
                                      String modelId) throws Exception {
        return runDetailed(prompt, apiKey, history, modelId, null, null);
    }

    public AgentRunResult runDetailed(String prompt, String apiKey, List<ChatMessage> history,
                                      String modelId, String agentId, AgentMode modeOverride) throws Exception {
        return runResolved(prompt, apiKey, history, options(modelId, agentId, modeOverride));
    }

    public AgentRunResult runDetailed(String prompt, String apiKey, List<ChatMessage> history,
                                      AgentExecutionOptions executionOptions) throws Exception {
        if (executionOptions == null) throw new IllegalArgumentException("executionOptions must not be null");
        return runResolved(prompt, apiKey, history, new RunOptions(executionOptions.modelId(), executionOptions.mode(),
                executionOptions.maxTurns(), executionOptions.systemPrompt(), executionOptions.allowedToolNames(),
                executionOptions.skillIds()));
    }

    private AgentRunResult runResolved(String prompt, String apiKey, List<ChatMessage> history,
                                       RunOptions options) throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        List<AgentTraceEvent> trace = new ArrayList<AgentTraceEvent>();
        messages.add(ChatMessage.system(systemPrompt(options)));
        messages.addAll(history);
        messages.add(ChatMessage.user(prompt));
        List<io.github.git13166956007.dsh.tool.ToolDefinition> definitions = options.mode().toolsEnabled()
                ? tools.definitions(options.allowedToolNames()) : List.of();

        for (int turn = 0; turn < options.maxTurns(); turn++) {
            ModelResponse response = model.complete(messages, definitions, apiKey, options.modelId());
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
                    result = tools.execute(call.name(), call.arguments(), options.allowedToolNames());
                } catch (Exception exception) {
                    result = "Tool execution failed: " + exception.getMessage();
                }
                trace.add(AgentTraceEvent.tool(call.name(), call.arguments(), result));
                messages.add(ChatMessage.tool(call.id(), result));
            }
        }

        throw new IllegalStateException("agent exceeded max turns: " + options.maxTurns());
    }

    public AgentRunResult runStreaming(String prompt, String apiKey, AgentStreamListener listener) throws Exception {
        return runStreaming(prompt, apiKey, List.of(), listener);
    }

    public AgentRunResult runStreaming(String prompt, String apiKey, List<ChatMessage> history,
                                       AgentStreamListener listener) throws Exception {
        return runStreaming(prompt, apiKey, history, null, listener);
    }

    public AgentRunResult runStreaming(String prompt, String apiKey, List<ChatMessage> history,
                                       String modelId, AgentStreamListener listener) throws Exception {
        return runStreaming(prompt, apiKey, history, modelId, null, null, listener);
    }

    public AgentRunResult runStreaming(String prompt, String apiKey, List<ChatMessage> history,
                                       String modelId, String agentId, AgentMode modeOverride,
                                       AgentStreamListener listener) throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }

        RunOptions options = options(modelId, agentId, modeOverride);
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        List<AgentTraceEvent> trace = new ArrayList<AgentTraceEvent>();
        messages.add(ChatMessage.system(systemPrompt(options)));
        messages.addAll(history);
        messages.add(ChatMessage.user(prompt));
        List<io.github.git13166956007.dsh.tool.ToolDefinition> definitions = options.mode().toolsEnabled()
                ? tools.definitions(options.allowedToolNames()) : List.of();

        for (int turn = 0; turn < options.maxTurns(); turn++) {
            ModelResponse response = model.stream(messages, definitions, apiKey, options.modelId(), listener::onText);
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
                    result = tools.execute(call.name(), call.arguments(), options.allowedToolNames());
                } catch (Exception exception) {
                    result = "Tool execution failed: " + exception.getMessage();
                }
                AgentTraceEvent event = AgentTraceEvent.tool(call.name(), call.arguments(), result);
                trace.add(event);
                listener.onToolResult(event);
                messages.add(ChatMessage.tool(call.id(), result));
            }
        }

        throw new IllegalStateException("agent exceeded max turns: " + options.maxTurns());
    }

    private RunOptions options(String modelId, String agentId, AgentMode modeOverride) {
        if (profiles == null) {
            return new RunOptions(blankToNull(modelId), modeOverride == null ? AgentMode.CHAT : modeOverride,
                    maxTurns, "", null, null);
        }
        AgentProfileData profile = profiles.resolve(agentId);
        return new RunOptions(blankToNull(modelId) == null ? profile.modelId() : blankToNull(modelId),
                modeOverride == null ? profile.mode() : modeOverride, profile.maxTurns(), profile.systemPrompt(), null, null);
    }

    private String systemPrompt(RunOptions options) {
        String base = skills == null ? "You are a helpful assistant. Use available tools when they are useful, then give a concise final answer."
                : skills.systemPrompt(options.skillIds());
        String modeInstruction = switch (options.mode()) {
            case CHAT -> "Stay conversational and use tools only when they help answer the request.";
            case PLANNING -> "You are in planning mode. Do not execute tools. Produce a clear, ordered plan with assumptions, dependencies, and verification steps.";
            case EXECUTION -> "You are in execution mode. Carry out the requested plan with available tools, verify important results, and report what was completed.";
        };
        String custom = options.systemPrompt();
        if (custom == null || custom.isBlank()) return base + "\n\n" + modeInstruction;
        return base + "\n\n" + modeInstruction + "\n\nProfile instructions:\n" + custom;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private record RunOptions(String modelId, AgentMode mode, int maxTurns, String systemPrompt,
                              java.util.Set<String> allowedToolNames, java.util.Set<String> skillIds) {
    }
}
