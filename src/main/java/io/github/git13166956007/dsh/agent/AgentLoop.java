package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.tool.ToolApprovalRequiredException;
import io.github.git13166956007.dsh.skill.SkillRegistry;
import io.github.git13166956007.dsh.memory.MemoryManager;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.run.RunSpec;
import java.util.ArrayList;
import java.util.List;

public final class AgentLoop {
    private final ChatModel model;
    private final ToolRegistry tools;
    private final SkillRegistry skills;
    private final AgentProfileRegistry profiles;
    private final MemoryManager memories;
    private final RunManager runs;
    private final int maxTurns;

    public AgentLoop(ChatModel model, ToolRegistry tools, int maxTurns) {
        this(model, tools, null, null, null, maxTurns);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, SkillRegistry skills, int maxTurns) {
        this(model, tools, skills, null, null, maxTurns);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, SkillRegistry skills,
                     AgentProfileRegistry profiles, int maxTurns) {
        this(model, tools, skills, profiles, null, maxTurns);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, SkillRegistry skills,
                     AgentProfileRegistry profiles, MemoryManager memories, int maxTurns) {
        this(model, tools, skills, profiles, memories, null, maxTurns);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, SkillRegistry skills,
                     AgentProfileRegistry profiles, MemoryManager memories, RunManager runs, int maxTurns) {
        if (maxTurns < 1) throw new IllegalArgumentException("maxTurns must be positive");
        this.model = model;
        this.tools = tools;
        this.skills = skills;
        this.profiles = profiles;
        this.memories = memories;
        this.runs = runs;
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
        return runResolved(prompt, apiKey, history, options(modelId, agentId, modeOverride, null, null),
                AgentRunContext.standalone());
    }

    public AgentRunResult runDetailed(String prompt, String apiKey, List<ChatMessage> history,
                                      String modelId, String agentId, AgentMode modeOverride,
                                      String memoryNamespace, String memorySubjectKey) throws Exception {
        return runResolved(prompt, apiKey, history,
                options(modelId, agentId, modeOverride, memoryNamespace, memorySubjectKey),
                AgentRunContext.standalone());
    }

    public AgentRunResult runDetailed(String prompt, String apiKey, List<ChatMessage> history,
                                      String modelId, String agentId, AgentMode modeOverride,
                                      String memoryNamespace, String memorySubjectKey,
                                      AgentRunContext context) throws Exception {
        return runResolved(prompt, apiKey, history,
                options(modelId, agentId, modeOverride, memoryNamespace, memorySubjectKey), context);
    }

    public AgentRunResult runDetailed(String prompt, String apiKey, List<ChatMessage> history,
                                      AgentExecutionOptions executionOptions) throws Exception {
        if (executionOptions == null) throw new IllegalArgumentException("executionOptions must not be null");
        return runResolved(prompt, apiKey, history, new RunOptions(executionOptions.modelId(), executionOptions.mode(),
                executionOptions.maxTurns(), executionOptions.systemPrompt(), executionOptions.allowedToolNames(),
                executionOptions.skillIds(), null, null, null), AgentRunContext.standalone());
    }

    public AgentRunResult runDetailed(String prompt, String apiKey, List<ChatMessage> history,
                                      AgentExecutionOptions executionOptions, AgentRunContext context) throws Exception {
        if (executionOptions == null) throw new IllegalArgumentException("executionOptions must not be null");
        return runResolved(prompt, apiKey, history, new RunOptions(executionOptions.modelId(), executionOptions.mode(),
                executionOptions.maxTurns(), executionOptions.systemPrompt(), executionOptions.allowedToolNames(),
                executionOptions.skillIds(), null, null, context.agentId()), context);
    }

    private AgentRunResult runResolved(String prompt, String apiKey, List<ChatMessage> history,
                                       RunOptions options, AgentRunContext context) throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }

        String runId = beginRun(options, context);
        try {
            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            List<AgentTraceEvent> trace = new ArrayList<AgentTraceEvent>();
            messages.add(ChatMessage.system(systemPrompt(options, prompt)));
            messages.addAll(history);
            messages.add(ChatMessage.user(prompt));
            List<io.github.git13166956007.dsh.tool.ToolDefinition> definitions = options.mode().toolsEnabled()
                    ? tools.definitions(options.allowedToolNames()) : List.of();

            for (int turn = 0; turn < options.maxTurns(); turn++) {
                ModelResponse response = model.complete(messages, definitions, apiKey, options.modelId());
                recordEvent(runId, "model_response", response.content());
                messages.add(ChatMessage.assistant(response.content(), response.toolCalls()));
                if (response.content() != null && !response.content().isEmpty()) {
                    trace.add(AgentTraceEvent.model(response.content()));
                }
                if (response.toolCalls().isEmpty()) {
                    String answer = response.content() == null ? "" : response.content();
                    finishRun(runId, answer);
                    return new AgentRunResult(answer, trace, turn + 1, runId);
                }

                for (ToolCall call : response.toolCalls()) {
                    recordEvent(runId, "tool_call", call.name() + " " + call.arguments());
                    String result;
                    try {
                        result = tools.execute(call.name(), call.arguments(), options.allowedToolNames());
                    } catch (ToolApprovalRequiredException exception) {
                        result = "Tool approval required: " + call.name();
                        recordEvent(runId, "tool_approval_required", call.name());
                    } catch (Exception exception) {
                        result = "Tool execution failed: " + exception.getMessage();
                    }
                    recordEvent(runId, "tool_result", call.name() + " " + result);
                    trace.add(AgentTraceEvent.tool(call.name(), call.arguments(), result));
                    messages.add(ChatMessage.tool(call.id(), result));
                }
            }

            throw new IllegalStateException("agent exceeded max turns: " + options.maxTurns());
        } catch (Exception exception) {
            failRun(runId, exception);
            throw exception;
        }
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
        return runStreaming(prompt, apiKey, history, modelId, agentId, modeOverride,
                null, null, listener);
    }

    public AgentRunResult runStreaming(String prompt, String apiKey, List<ChatMessage> history,
                                       String modelId, String agentId, AgentMode modeOverride,
                                       String memoryNamespace, String memorySubjectKey,
                                       AgentStreamListener listener) throws Exception {
        return runStreaming(prompt, apiKey, history, modelId, agentId, modeOverride, memoryNamespace,
                memorySubjectKey, AgentRunContext.standalone(), listener);
    }

    public AgentRunResult runStreaming(String prompt, String apiKey, List<ChatMessage> history,
                                       String modelId, String agentId, AgentMode modeOverride,
                                       String memoryNamespace, String memorySubjectKey,
                                       AgentRunContext context, AgentStreamListener listener) throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }

        RunOptions options = options(modelId, agentId, modeOverride, memoryNamespace, memorySubjectKey);
        String runId = beginRun(options, context);
        try {
            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            List<AgentTraceEvent> trace = new ArrayList<AgentTraceEvent>();
            messages.add(ChatMessage.system(systemPrompt(options, prompt)));
            messages.addAll(history);
            messages.add(ChatMessage.user(prompt));
            List<io.github.git13166956007.dsh.tool.ToolDefinition> definitions = options.mode().toolsEnabled()
                    ? tools.definitions(options.allowedToolNames()) : List.of();

            for (int turn = 0; turn < options.maxTurns(); turn++) {
                ModelResponse response = model.stream(messages, definitions, apiKey, options.modelId(), delta -> {
                    recordEventUnchecked(runId, "model_delta", delta);
                    listener.onText(delta);
                });
                recordEvent(runId, "model_response", response.content());
                messages.add(ChatMessage.assistant(response.content(), response.toolCalls()));
                if (response.content() != null && !response.content().isEmpty()) {
                    trace.add(AgentTraceEvent.model(response.content()));
                }
                if (response.toolCalls().isEmpty()) {
                    String answer = response.content() == null ? "" : response.content();
                    finishRun(runId, answer);
                    return new AgentRunResult(answer, trace, turn + 1, runId);
                }

                for (ToolCall call : response.toolCalls()) {
                    recordEvent(runId, "tool_call", call.name() + " " + call.arguments());
                    listener.onToolCall(call);
                    String result;
                    try {
                        result = tools.execute(call.name(), call.arguments(), options.allowedToolNames());
                    } catch (ToolApprovalRequiredException exception) {
                        result = "Tool approval required: " + call.name();
                        recordEvent(runId, "tool_approval_required", call.name());
                    } catch (Exception exception) {
                        result = "Tool execution failed: " + exception.getMessage();
                    }
                    AgentTraceEvent event = AgentTraceEvent.tool(call.name(), call.arguments(), result);
                    recordEvent(runId, "tool_result", call.name() + " " + result);
                    trace.add(event);
                    listener.onToolResult(event);
                    messages.add(ChatMessage.tool(call.id(), result));
                }
            }

            throw new IllegalStateException("agent exceeded max turns: " + options.maxTurns());
        } catch (Exception exception) {
            failRun(runId, exception);
            throw exception;
        }
    }

    private RunOptions options(String modelId, String agentId, AgentMode modeOverride) {
        return options(modelId, agentId, modeOverride, null, null);
    }

    private RunOptions options(String modelId, String agentId, AgentMode modeOverride,
                               String memoryNamespace, String memorySubjectKey) {
        if (profiles == null) {
            return new RunOptions(blankToNull(modelId), modeOverride == null ? AgentMode.CHAT : modeOverride,
                    maxTurns, "", null, null, memoryNamespace, memorySubjectKey, agentId);
        }
        AgentProfileData profile = profiles.resolve(agentId);
        return new RunOptions(blankToNull(modelId) == null ? profile.modelId() : blankToNull(modelId),
                modeOverride == null ? profile.mode() : modeOverride, profile.maxTurns(), profile.systemPrompt(), null, null,
                memoryNamespace, memorySubjectKey, agentId);
    }

    private String systemPrompt(RunOptions options, String query) throws Exception {
        String base = skills == null ? "You are a helpful assistant. Use available tools when they are useful, then give a concise final answer."
                : skills.systemPrompt(options.skillIds());
        String modeInstruction = switch (options.mode()) {
            case CHAT -> "Stay conversational and use tools only when they help answer the request.";
            case PLANNING -> "You are in planning mode. Do not execute tools. Produce a clear, ordered plan with assumptions, dependencies, and verification steps.";
            case EXECUTION -> "You are in execution mode. Carry out the requested plan with available tools, verify important results, and report what was completed.";
        };
        String custom = options.systemPrompt();
        String result = custom == null || custom.isBlank() ? base + "\n\n" + modeInstruction
                : base + "\n\n" + modeInstruction + "\n\nProfile instructions:\n" + custom;
        if (memories != null && options.memoryNamespace() != null && options.memorySubjectKey() != null) {
            String context = memories.context(options.memoryNamespace(), options.memorySubjectKey(), query, 5);
            if (!context.isBlank()) result += "\n\n" + context;
        }
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String beginRun(RunOptions options, AgentRunContext context) throws Exception {
        if (runs == null) return null;
        AgentRunContext actual = context == null ? AgentRunContext.standalone() : context;
        return runs.start(new RunSpec(actual.parentRunId(), actual.kind(), actual.conversationId(), actual.planId(),
                actual.stepId(), actual.agentId() == null ? options.agentId() : actual.agentId(), options.modelId()));
    }

    private void finishRun(String runId, String answer) throws Exception {
        if (runId != null) runs.complete(runId, answer);
    }

    private void failRun(String runId, Exception exception) {
        if (runId == null) return;
        try {
            runs.fail(runId, exception.getMessage());
        } catch (Exception auditFailure) {
            exception.addSuppressed(auditFailure);
        }
    }

    private void recordEvent(String runId, String type, String payload) throws Exception {
        if (runId != null) runs.event(runId, type, payload);
    }

    private void recordEventUnchecked(String runId, String type, String payload) {
        try {
            recordEvent(runId, type, payload);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to persist run event", exception);
        }
    }

        private record RunOptions(String modelId, AgentMode mode, int maxTurns, String systemPrompt,
                              java.util.Set<String> allowedToolNames, java.util.Set<String> skillIds,
                              String memoryNamespace, String memorySubjectKey, String agentId) {
    }
}
