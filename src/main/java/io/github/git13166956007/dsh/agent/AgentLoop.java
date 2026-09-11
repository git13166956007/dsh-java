package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.tool.ToolApprovalRequiredException;
import io.github.git13166956007.dsh.skill.SkillRegistry;
import io.github.git13166956007.dsh.memory.MemoryManager;
import io.github.git13166956007.dsh.run.Run;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.run.RunSpec;
import io.github.git13166956007.dsh.context.ContextManager;
import io.github.git13166956007.dsh.context.ContextRequest;
import io.github.git13166956007.dsh.context.ContextSnapshot;
import io.github.git13166956007.dsh.core.profile.ProfilePatch;
import io.github.git13166956007.dsh.core.profile.RuntimeProfile;
import io.github.git13166956007.dsh.core.scope.Scope;
import io.github.git13166956007.dsh.event.EventBus;
import io.github.git13166956007.dsh.plugin.DshServices;
import io.github.git13166956007.dsh.service.ServiceKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.git13166956007.dsh.tool.ToolDefinition;

public final class AgentLoop implements AutoCloseable {
    private static final String DELEGATE_TOOL = "delegate_to_subagent";
    private final ObjectMapper objectMapper;
    private final int maxTurns;
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool();
    private volatile SubAgentRunner subAgents;
    private final Map<String, PendingExecution> pendingApprovals = new ConcurrentHashMap<String, PendingExecution>();
    private final Map<String, Future<?>> activeRuns = new ConcurrentHashMap<String, Future<?>>();
    private final Map<String, Thread> activeThreads = new ConcurrentHashMap<String, Thread>();
    private final java.util.Set<String> cancelledRuns = ConcurrentHashMap.newKeySet();
    private volatile Scope runtimeScope;
    private final ThreadLocal<Scope> executionScopes = new ThreadLocal<Scope>();

    public AgentLoop(Scope runtimeScope, int maxTurns) {
        this(runtimeScope, maxTurns, null);
    }

    private AgentLoop(Scope runtimeScope, int maxTurns, ObjectMapper objectMapper) {
        if (runtimeScope == null) throw new IllegalArgumentException("runtime scope must not be null");
        if (maxTurns < 1) throw new IllegalArgumentException("maxTurns must be positive");
        this.runtimeScope = runtimeScope;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.maxTurns = maxTurns;
    }

    public void setSubAgentRunner(SubAgentRunner subAgents) {
        this.subAgents = subAgents;
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
                executionOptions.skillIds(), executionOptions.maxToolCalls(), executionOptions.timeoutSeconds(),
                executionOptions.maxDepth(), null, null, null, executionOptions.permissions()), AgentRunContext.standalone());
    }

    public AgentRunResult runDetailed(String prompt, String apiKey, List<ChatMessage> history,
                                      AgentExecutionOptions executionOptions, AgentRunContext context) throws Exception {
        if (executionOptions == null) throw new IllegalArgumentException("executionOptions must not be null");
        return runResolved(prompt, apiKey, history, runOptions(executionOptions, context), context);
    }

    /** Starts a durable Agent run without blocking the caller thread. */
    public AgentRunHandle runAsync(String prompt, String apiKey, List<ChatMessage> history,
                                   AgentExecutionOptions executionOptions, AgentRunContext context) throws Exception {
        if (executionOptions == null) throw new IllegalArgumentException("executionOptions must not be null");
        if (runManager() == null) throw new IllegalStateException("async agent runs require a RunManager");
        RunOptions options = effectiveOptions(runOptions(executionOptions, context));
        validatePrompt(prompt);
        String runId = beginRun(options, context);
        return submitAsync(runId, prompt, apiKey, history, options, context);
    }

    /** Reattaches an async execution to an existing durable Run after a restart. */
    public AgentRunHandle resumeAsync(String runId, String prompt, String apiKey, List<ChatMessage> history,
                                      AgentExecutionOptions executionOptions, AgentRunContext context) throws Exception {
        if (executionOptions == null) throw new IllegalArgumentException("executionOptions must not be null");
        if (runManager() == null) throw new IllegalStateException("async agent runs require a RunManager");
        Run run = runManager().find(runId);
        if (run == null) throw new IllegalArgumentException("unknown run: " + runId);
        if (run.status() != io.github.git13166956007.dsh.run.RunStatus.RUNNING) {
            throw new IllegalStateException("run is not recoverable: " + runId);
        }
        RunOptions options = effectiveOptions(runOptions(executionOptions, context));
        validatePrompt(prompt);
        return submitAsync(runId, prompt, apiKey, history, options, context);
    }

    private AgentRunHandle submitAsync(String runId, String prompt, String apiKey, List<ChatMessage> history,
                                        RunOptions options, AgentRunContext context) throws Exception {
        persistAsyncRequest(runId, prompt, history, options);
        CompletableFuture<AgentRunResult> result = new CompletableFuture<AgentRunResult>();
        Future<?> task = asyncExecutor.submit(() -> {
            try {
                result.complete(runResolved(prompt, apiKey, history, options, context, runId));
            } catch (Throwable exception) {
                result.completeExceptionally(exception);
            } finally {
                activeRuns.remove(runId);
                cancelledRuns.remove(runId);
            }
        });
        activeRuns.put(runId, task);
        if (result.isDone()) activeRuns.remove(runId, task);
        return new AgentRunHandle(runId, result, () -> cancel(runId));
    }

    private AgentRunResult runResolved(String prompt, String apiKey, List<ChatMessage> history,
                                       RunOptions options, AgentRunContext context) throws Exception {
        validatePrompt(prompt);
        options = effectiveOptions(options);
        String runId = beginRun(options, context);
        return runResolved(prompt, apiKey, history, options, context, runId);
    }

    private AgentRunResult runResolved(String prompt, String apiKey, List<ChatMessage> history,
                                       RunOptions options, AgentRunContext context, String runId) throws Exception {
        if (runId != null) activeThreads.put(runId, Thread.currentThread());
        try {
            Scope scope = openExecutionScope(context, options, runId);
            executionScopes.set(scope);
            emitRunEvent(runId, options, AgentEvents.Phase.STARTED, null);
            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            List<ChatMessage> conversationMessages = new ArrayList<ChatMessage>();
            List<AgentTraceEvent> trace = new ArrayList<AgentTraceEvent>();
            ExecutionBudget budget = new ExecutionBudget(options);
            ChatModel activeModel = model();
            messages.add(ChatMessage.system(systemPrompt(options, prompt, context)));
            messages.addAll(history);
            messages.add(ChatMessage.user(prompt));
            List<ToolDefinition> definitions = definitions(options);

            for (int turn = 0; turn < options.maxTurns(); turn++) {
                budget.check();
                emitRunEvent(runId, options, AgentEvents.Phase.MODEL_REQUESTED, "turn=" + turn);
                ModelResponse response = complete(activeModel, runId, messages, definitions, apiKey, options.modelId());
                recordEvent(runId, "model_response", response.content());
                ChatMessage assistantMessage = ChatMessage.assistant(response.content(), response.toolCalls(),
                        response.reasoningContent());
                messages.add(assistantMessage);
                conversationMessages.add(assistantMessage);
                if (hasModelOutput(response)) {
                    trace.add(AgentTraceEvent.model(response.content(), response));
                }
                if (response.toolCalls().isEmpty()) {
                    String answer = response.content() == null ? "" : response.content();
                    finishRun(runId, answer);
                    emitRunEvent(runId, options, AgentEvents.Phase.COMPLETED, answer);
                    return new AgentRunResult(answer, trace, turn + 1, runId, null, conversationMessages);
                }

                for (ToolCall call : response.toolCalls()) {
                    budget.beforeToolCall();
                    recordEvent(runId, "tool_call", call.name() + " " + call.arguments());
                    String result;
                    try {
                        call = rewriteToolCall(runId, call);
                        emitRunEvent(runId, options, AgentEvents.Phase.TOOL_REQUESTED, call.name());
                        result = executeTool(call, options, apiKey, runId, budget);
                    } catch (SubAgentApprovalRequiredException exception) {
                        PendingToolApproval approval = new PendingToolApproval(call.id(), call.name(), call.arguments(),
                                exception.pendingResult().runId());
                        String approvalRunId = pauseForApproval(runId, messages, trace, turn + 1, options, apiKey,
                                definitions, budget, approval);
                        return new AgentRunResult("", trace, turn + 1, approvalRunId, approval, conversationMessages);
                    } catch (ToolApprovalRequiredException exception) {
                        PendingToolApproval approval = new PendingToolApproval(call.id(), call.name(), call.arguments());
                        String approvalRunId = pauseForApproval(runId, messages, trace, turn + 1, options, apiKey,
                                definitions, budget, approval);
                        return new AgentRunResult("", trace, turn + 1, approvalRunId, approval, conversationMessages);
                    } catch (EventBus.EventRejectedException exception) {
                        throw exception;
                    } catch (Exception exception) {
                        result = "Tool execution failed: " + exception.getMessage();
                    }
                    result = rewriteToolResult(runId, call, result);
                    recordEvent(runId, "tool_result", call.name() + " " + result);
                    trace.add(AgentTraceEvent.tool(call.name(), call.arguments(), result));
                    ChatMessage toolMessage = ChatMessage.tool(call.id(), result);
                    messages.add(toolMessage);
                    conversationMessages.add(toolMessage);
                    emitRunEvent(runId, options, AgentEvents.Phase.TOOL_COMPLETED, call.name());
                }
            }

            throw new IllegalStateException("agent exceeded max turns: " + options.maxTurns());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancelRun(runId);
            throw exception;
        } catch (Exception exception) {
            if (runId != null && cancelledRuns.contains(runId)) {
                cancelRun(runId);
                throw exception;
            }
            failRun(runId, exception);
            emitRunEventUnchecked(runId, options, AgentEvents.Phase.FAILED, exception.getMessage());
            throw exception;
        } finally {
            if (runId != null) activeThreads.remove(runId, Thread.currentThread());
            Scope scope = executionScopes.get();
            executionScopes.remove();
            if (scope != null) scope.close();
        }
    }

    private static void validatePrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
    }

    private static boolean hasModelOutput(ModelResponse response) {
        return response != null && ((response.content() != null && !response.content().isEmpty())
                || (response.reasoningContent() != null && !response.reasoningContent().isEmpty()));
    }

    private Scope openExecutionScope(AgentRunContext context, RunOptions options, String runId) {
        if (runtimeScope == null) return null;
        String id = "run:" + (runId == null ? UUID.randomUUID() : runId);
        Scope scope = runtimeScope.child(id);
        if (options != null) {
            scope.withProfile(new ProfilePatch(options.modelId(), options.systemPrompt(),
                    options.allowedToolNames(), options.skillIds(), options.permissions())
                    .apply(runtimeScope.profile(), id));
        }
        return scope;
    }

    private <T> T resolve(ServiceKey<T> key) {
        Scope current = executionScopes.get();
        if (current != null) {
            T local = current.local(key);
            if (local != null) return local;
            try { return current.resolve(key); } catch (IllegalStateException ignored) { }
        }
        if (runtimeScope == null) throw new IllegalStateException("agent runtime scope is not bound");
        return runtimeScope.resolve(key);
    }

    private <T> T optional(ServiceKey<T> key) {
        try { return resolve(key); } catch (IllegalStateException ignored) { return null; }
    }

    private ChatModel model() { return resolve(DshServices.CHAT_MODEL); }

    private ToolRegistry toolRegistry() { return resolve(DshServices.TOOLS); }

    private SkillRegistry skillRegistry() { return optional(DshServices.SKILLS); }

    private AgentProfileRegistry profileRegistry() { return optional(DshServices.AGENTS); }

    private MemoryManager memoryManager() { return optional(DshServices.MEMORIES); }

    private RunManager runManager() { return optional(DshServices.RUNS); }

    private ContextManager contextManager() { return optional(DshServices.CONTEXT); }

    private EventBus eventBus() { return optional(DshServices.EVENTS); }

    private AgentContinuationStore continuationStore() { return optional(DshServices.CONTINUATIONS); }

    private void emitRunEvent(String runId, RunOptions options, AgentEvents.Phase phase, String detail)
            throws Exception {
        EventBus bus = eventBus();
        if (bus != null) bus.rewrite(AgentEvents.RUN,
                new AgentEvents.RunEvent(runId, options == null ? null : options.agentId(), phase, detail));
    }

    private void emitRunEventUnchecked(String runId, RunOptions options, AgentEvents.Phase phase, String detail) {
        try { emitRunEvent(runId, options, phase, detail); }
        catch (Exception exception) { throw new IllegalStateException("agent lifecycle event rejected", exception); }
    }

    private ModelResponse complete(ChatModel activeModel, String runId, List<ChatMessage> messages,
                                   List<ToolDefinition> definitions, String apiKey, String modelId) throws Exception {
        EventBus bus = eventBus();
        AgentEvents.ModelRequest request = new AgentEvents.ModelRequest(runId, messages, definitions, apiKey, modelId, false);
        if (bus != null) request = bus.rewrite(AgentEvents.MODEL_REQUEST, request);
        ModelResponse response = activeModel.complete(request.messages(), request.tools(), request.apiKey(), request.modelId());
        if (bus != null) response = bus.rewrite(AgentEvents.MODEL_RESPONSE,
                new AgentEvents.ModelResponseEvent(runId, response)).response();
        return response;
    }

    private ModelResponse stream(ChatModel activeModel, String runId, List<ChatMessage> messages,
                                 List<ToolDefinition> definitions, String apiKey, String modelId,
                                 AgentStreamListener listener) throws Exception {
        EventBus bus = eventBus();
        AgentEvents.ModelRequest request = new AgentEvents.ModelRequest(runId, messages, definitions, apiKey, modelId, true);
        if (bus != null) request = bus.rewrite(AgentEvents.MODEL_REQUEST, request);
        ModelStreamListener streamListener = new ModelStreamListener() {
            @Override public void onText(String delta) {
                String value = rewriteDelta(runId, "text", delta);
                listener.onText(value);
            }
            @Override public void onReasoning(String delta) {
                String value = rewriteDelta(runId, "reasoning", delta);
                listener.onReasoning(value);
            }
        };
        ModelResponse response = activeModel.stream(request.messages(), request.tools(), request.apiKey(), request.modelId(), streamListener);
        if (bus != null) response = bus.rewrite(AgentEvents.MODEL_RESPONSE,
                new AgentEvents.ModelResponseEvent(runId, response)).response();
        return response;
    }

    private String rewriteDelta(String runId, String kind, String delta) {
        try {
            EventBus bus = eventBus();
            if (bus == null) return delta;
            return bus.rewrite(AgentEvents.STREAM_DELTA, new AgentEvents.StreamDelta(runId, kind, delta)).delta();
        } catch (Exception exception) {
            throw new IllegalStateException("model stream event rejected", exception);
        }
    }

    private ToolCall rewriteToolCall(String runId, ToolCall call) throws Exception {
        EventBus bus = eventBus();
        return bus == null ? call : bus.rewrite(AgentEvents.TOOL_CALL, new AgentEvents.ToolCallEvent(runId, call)).call();
    }

    private String rewriteToolResult(String runId, ToolCall call, String result) throws Exception {
        EventBus bus = eventBus();
        return bus == null ? result : bus.rewrite(AgentEvents.TOOL_RESULT,
                new AgentEvents.ToolResultEvent(runId, call, result)).result();
    }

    private static RunOptions runOptions(AgentExecutionOptions options, AgentRunContext context) {
        return new RunOptions(options.modelId(), options.mode(), options.maxTurns(), options.systemPrompt(),
                options.allowedToolNames(), options.skillIds(), options.maxToolCalls(), options.timeoutSeconds(),
                options.maxDepth(), null, null, context == null ? null : context.agentId(), options.permissions());
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

        RunOptions options = effectiveOptions(options(modelId, agentId, modeOverride, memoryNamespace, memorySubjectKey));
        String runId = beginRun(options, context);
        if (runId != null) activeThreads.put(runId, Thread.currentThread());
        try {
            Scope scope = openExecutionScope(context, options, runId);
            executionScopes.set(scope);
            emitRunEvent(runId, options, AgentEvents.Phase.STARTED, null);
            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            List<ChatMessage> conversationMessages = new ArrayList<ChatMessage>();
            List<AgentTraceEvent> trace = new ArrayList<AgentTraceEvent>();
            ExecutionBudget budget = new ExecutionBudget(options);
            ChatModel activeModel = model();
            messages.add(ChatMessage.system(systemPrompt(options, prompt, context)));
            messages.addAll(history);
            messages.add(ChatMessage.user(prompt));
            List<ToolDefinition> definitions = definitions(options);

            for (int turn = 0; turn < options.maxTurns(); turn++) {
                budget.check();
                emitRunEvent(runId, options, AgentEvents.Phase.MODEL_REQUESTED, "turn=" + turn);
                ModelResponse response = stream(activeModel, runId, messages, definitions, apiKey, options.modelId(),
                        new AgentStreamListener() {
                            @Override public void onText(String delta) {
                                recordEventUnchecked(runId, "model_delta", delta);
                                listener.onText(delta);
                            }
                            @Override public void onReasoning(String delta) {
                                recordEventUnchecked(runId, "reasoning_delta", delta);
                                listener.onReasoning(delta);
                            }
                            @Override public void onToolCall(ToolCall call) { }
                            @Override public void onToolResult(AgentTraceEvent result) { }
                        });
                recordEvent(runId, "model_response", response.content());
                ChatMessage assistantMessage = ChatMessage.assistant(response.content(), response.toolCalls(),
                        response.reasoningContent());
                messages.add(assistantMessage);
                conversationMessages.add(assistantMessage);
                if (hasModelOutput(response)) {
                    trace.add(AgentTraceEvent.model(response.content(), response));
                }
                if (response.toolCalls().isEmpty()) {
                    String answer = response.content() == null ? "" : response.content();
                    finishRun(runId, answer);
                    emitRunEvent(runId, options, AgentEvents.Phase.COMPLETED, answer);
                    return new AgentRunResult(answer, trace, turn + 1, runId, null, conversationMessages);
                }

                for (ToolCall call : response.toolCalls()) {
                    budget.beforeToolCall();
                    recordEvent(runId, "tool_call", call.name() + " " + call.arguments());
                    String result;
                    try {
                        call = rewriteToolCall(runId, call);
                        emitRunEvent(runId, options, AgentEvents.Phase.TOOL_REQUESTED, call.name());
                        listener.onToolCall(call);
                        result = executeTool(call, options, apiKey, runId, budget);
                    } catch (SubAgentApprovalRequiredException exception) {
                        PendingToolApproval approval = new PendingToolApproval(call.id(), call.name(), call.arguments(),
                                exception.pendingResult().runId());
                        String approvalRunId = pauseForApproval(runId, messages, trace, turn + 1, options, apiKey,
                                definitions, budget, approval);
                        return new AgentRunResult("", trace, turn + 1, approvalRunId, approval, conversationMessages);
                    } catch (ToolApprovalRequiredException exception) {
                        PendingToolApproval approval = new PendingToolApproval(call.id(), call.name(), call.arguments());
                        String approvalRunId = pauseForApproval(runId, messages, trace, turn + 1, options, apiKey,
                                definitions, budget, approval);
                        return new AgentRunResult("", trace, turn + 1, approvalRunId, approval, conversationMessages);
                    } catch (EventBus.EventRejectedException exception) {
                        throw exception;
                    } catch (Exception exception) {
                        result = "Tool execution failed: " + exception.getMessage();
                    }
                    result = rewriteToolResult(runId, call, result);
                    AgentTraceEvent event = AgentTraceEvent.tool(call.name(), call.arguments(), result);
                    recordEvent(runId, "tool_result", call.name() + " " + result);
                    trace.add(event);
                    listener.onToolResult(event);
                    emitRunEvent(runId, options, AgentEvents.Phase.TOOL_COMPLETED, call.name());
                    ChatMessage toolMessage = ChatMessage.tool(call.id(), result);
                    messages.add(toolMessage);
                    conversationMessages.add(toolMessage);
                }
            }

            throw new IllegalStateException("agent exceeded max turns: " + options.maxTurns());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancelRun(runId);
            throw exception;
        } catch (Exception exception) {
            if (runId != null && cancelledRuns.contains(runId)) {
                cancelRun(runId);
                throw exception;
            }
            failRun(runId, exception);
            emitRunEventUnchecked(runId, options, AgentEvents.Phase.FAILED, exception.getMessage());
            throw exception;
        } finally {
            if (runId != null) activeThreads.remove(runId, Thread.currentThread());
            Scope scope = executionScopes.get();
            executionScopes.remove();
            if (scope != null) scope.close();
        }
    }

    public AgentRunResult resumeApproval(String runId, boolean approved) throws Exception {
        return resumeApproval(runId, approved, null);
    }

    public AgentRunResult resumeApproval(String runId, boolean approved, String requestApiKey) throws Exception {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId must not be blank");
        PendingExecution pending = pendingApprovals.remove(runId);
        if (pending == null) pending = restoreContinuation(runId);
        if (pending == null) throw new IllegalArgumentException("run is not awaiting tool approval: " + runId);
        String effectiveApiKey = blankToNull(requestApiKey);
        if (effectiveApiKey != null) pending.apiKey = effectiveApiKey;
        try {
            deleteContinuation(runId);
            if (runManager() != null) {
                if (runManager().find(runId) == null) throw new IllegalArgumentException("unknown run: " + runId);
                runManager().resume(runId);
                recordEvent(runId, approved ? "tool_approval_granted" : "tool_approval_denied",
                        pending.approval.toolName());
            }
            String result;
            if (pending.approval.delegatedRunId() != null) {
                AgentRunResult child = resumeApproval(pending.approval.delegatedRunId(), approved, pending.apiKey);
                if (child.pendingApproval() != null) {
                    PendingToolApproval nextApproval = new PendingToolApproval(pending.approval.toolCallId(),
                            pending.approval.toolName(), pending.approval.arguments(), child.runId());
                    pauseForApproval(pending.runId, pending.messages, pending.trace, pending.nextTurn,
                            pending.options, pending.apiKey, pending.definitions, pending.budget, nextApproval);
                    return new AgentRunResult("", pending.trace, pending.nextTurn, pending.runId, nextApproval);
                }
                result = delegationResult(pending.approval.arguments(), child);
                recordEvent(pending.runId, "sub_agent_completed", result);
            } else if (!approved) {
                result = "Tool execution denied by user: " + pending.approval.toolName();
            } else {
                pending.budget.check();
                checkToolPermission(pending.approval.toolName(), pending.options);
                try {
                    result = toolRegistry().executeApproved(pending.approval.toolName(), pending.approval.arguments(),
                            pending.options.allowedToolNames());
                } catch (Exception exception) {
                    result = "Tool execution failed: " + exception.getMessage();
                }
            }
            recordEvent(runId, "tool_result", pending.approval.toolName() + " " + result);
            pending.trace.add(AgentTraceEvent.tool(pending.approval.toolName(), pending.approval.arguments(), result));
            ChatMessage toolMessage = ChatMessage.tool(pending.approval.toolCallId(), result);
            pending.messages.add(toolMessage);
            return continueDetailed(pending, new ArrayList<ChatMessage>(List.of(toolMessage)));
        } catch (Exception exception) {
            failRun(runId, exception);
            throw exception;
        }
    }

    public boolean cancelPendingApproval(String runId) {
        if (runId == null) return false;
        boolean removed = pendingApprovals.remove(runId) != null;
        try {
            deleteContinuation(runId);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to delete agent continuation", exception);
        }
        return removed;
    }

    /** Interrupts a live run and marks it cancelled without turning it into a failure. */
    public boolean cancel(String runId) {
        if (runId == null || runId.isBlank()) return false;
        Future<?> task = activeRuns.remove(runId);
        boolean cancelled = task != null && task.cancel(true);
        Thread thread = activeThreads.get(runId);
        if (thread != null) {
            thread.interrupt();
            cancelled = true;
        }
        boolean pending = pendingApprovals.containsKey(runId);
        if (!cancelled && !pending) return false;
        cancelledRuns.add(runId);
        cancelPendingApproval(runId);
        cancelRun(runId);
        return cancelled;
    }

    private AgentRunResult continueDetailed(PendingExecution pending, List<ChatMessage> conversationMessages) throws Exception {
        Scope existing = executionScopes.get();
        if (existing == null && runtimeScope != null) {
            Scope scope = openExecutionScope(AgentRunContext.standalone(), pending.options, pending.runId);
            executionScopes.set(scope);
            try {
                return continueDetailedInScope(pending, conversationMessages);
            } finally {
                executionScopes.remove();
                scope.close();
            }
        }
        return continueDetailedInScope(pending, conversationMessages);
    }

    private AgentRunResult continueDetailedInScope(PendingExecution pending, List<ChatMessage> conversationMessages) throws Exception {
        for (int turn = pending.nextTurn; turn < pending.options.maxTurns(); turn++) {
            pending.budget.check();
            emitRunEvent(pending.runId, pending.options, AgentEvents.Phase.MODEL_REQUESTED, "turn=" + turn);
            ModelResponse response = complete(model(), pending.runId, pending.messages, pending.definitions,
                    pending.apiKey, pending.options.modelId());
            recordEvent(pending.runId, "model_response", response.content());
            ChatMessage assistantMessage = ChatMessage.assistant(response.content(), response.toolCalls(),
                    response.reasoningContent());
            pending.messages.add(assistantMessage);
            conversationMessages.add(assistantMessage);
            if (hasModelOutput(response)) {
                pending.trace.add(AgentTraceEvent.model(response.content(), response));
            }
            if (response.toolCalls().isEmpty()) {
                String answer = response.content() == null ? "" : response.content();
                finishRun(pending.runId, answer);
                emitRunEvent(pending.runId, pending.options, AgentEvents.Phase.COMPLETED, answer);
                return new AgentRunResult(answer, pending.trace, turn + 1, pending.runId, null, conversationMessages);
            }
            for (ToolCall call : response.toolCalls()) {
                pending.budget.beforeToolCall();
                recordEvent(pending.runId, "tool_call", call.name() + " " + call.arguments());
                String result;
                try {
                    call = rewriteToolCall(pending.runId, call);
                    emitRunEvent(pending.runId, pending.options, AgentEvents.Phase.TOOL_REQUESTED, call.name());
                    result = executeTool(call, pending.options, pending.apiKey, pending.runId, pending.budget);
                } catch (SubAgentApprovalRequiredException exception) {
                    PendingToolApproval approval = new PendingToolApproval(call.id(), call.name(), call.arguments(),
                            exception.pendingResult().runId());
                    pauseForApproval(pending.runId, pending.messages, pending.trace, turn + 1, pending.options,
                            pending.apiKey, pending.definitions, pending.budget, approval);
                    return new AgentRunResult("", pending.trace, turn + 1, pending.runId, approval, conversationMessages);
                } catch (ToolApprovalRequiredException exception) {
                    PendingToolApproval approval = new PendingToolApproval(call.id(), call.name(), call.arguments());
                    pauseForApproval(pending.runId, pending.messages, pending.trace, turn + 1, pending.options,
                            pending.apiKey, pending.definitions, pending.budget, approval);
                    return new AgentRunResult("", pending.trace, turn + 1, pending.runId, approval, conversationMessages);
                } catch (EventBus.EventRejectedException exception) {
                    throw exception;
                } catch (Exception exception) {
                    result = "Tool execution failed: " + exception.getMessage();
                }
                result = rewriteToolResult(pending.runId, call, result);
                recordEvent(pending.runId, "tool_result", call.name() + " " + result);
                pending.trace.add(AgentTraceEvent.tool(call.name(), call.arguments(), result));
                ChatMessage toolMessage = ChatMessage.tool(call.id(), result);
                pending.messages.add(toolMessage);
                conversationMessages.add(toolMessage);
                emitRunEvent(pending.runId, pending.options, AgentEvents.Phase.TOOL_COMPLETED, call.name());
            }
        }
        throw new IllegalStateException("agent exceeded max turns: " + pending.options.maxTurns());
    }

    private String pauseForApproval(String runId, List<ChatMessage> messages, List<AgentTraceEvent> trace,
                                    int nextTurn, RunOptions options, String apiKey,
                                    List<io.github.git13166956007.dsh.tool.ToolDefinition> definitions,
                                    ExecutionBudget budget, PendingToolApproval approval) throws Exception {
        String actualRunId = runId == null ? UUID.randomUUID().toString() : runId;
        PendingExecution pending = new PendingExecution(actualRunId, messages, trace, nextTurn, options, apiKey,
                definitions, budget, approval);
        pendingApprovals.put(actualRunId, pending);
        persistContinuation(pending);
        if (runManager() != null) {
            runManager().waitForApproval(actualRunId, approval.toolName() + " " + approval.arguments());
        }
        return actualRunId;
    }

    private List<ToolDefinition> definitions(RunOptions options) {
        if (!options.mode().toolsEnabled()) return List.of();
        List<ToolDefinition> result = new ArrayList<ToolDefinition>(toolRegistry().definitions(options.allowedToolNames()));
        if (delegationAllowed(options)) result.add(delegationDefinition());
        return result;
    }

    private boolean delegationAllowed(RunOptions options) {
        return subAgents != null
                && (options.allowedToolNames() == null || options.allowedToolNames().contains(DELEGATE_TOOL))
                && !subAgents.delegableProfiles().isEmpty();
    }

    private ToolDefinition delegationDefinition() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("profileId").put("type", "string")
                .put("description", "The execution sub-agent profile ID.");
        properties.putObject("task").put("type", "string")
                .put("description", "A self-contained task for the sub-agent to complete.");
        schema.putArray("required").add("profileId").add("task");

        StringBuilder description = new StringBuilder("Delegate a self-contained task to an enabled execution sub-agent. Available profiles: ");
        boolean first = true;
        for (SubAgentProfile profile : subAgents.delegableProfiles()) {
            if (!first) description.append("; ");
            description.append(profile.id()).append(" (").append(profile.name())
                    .append(", tools=").append(profile.allowedToolNames())
                    .append(", skills=").append(profile.skillIds()).append(')');
            first = false;
        }
        return new ToolDefinition(DELEGATE_TOOL, description.toString(), schema);
    }

    private String executeTool(ToolCall call, RunOptions options, String apiKey, String runId,
                               ExecutionBudget budget) throws Exception {
        checkToolPermission(call.name(), options);
        if (!DELEGATE_TOOL.equals(call.name())) {
            return toolRegistry().execute(call.name(), call.arguments(), options.allowedToolNames());
        }
        if (!delegationAllowed(options)) {
            throw new IllegalStateException("sub-agent delegation is not allowed for this agent");
        }
        JsonNode arguments = call.arguments() == null ? objectMapper.createObjectNode() : call.arguments();
        String profileId = arguments.path("profileId").asString(null);
        String task = arguments.path("task").asString(null);
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("delegate_to_subagent requires profileId");
        }
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("delegate_to_subagent requires task");
        }
        if (!subAgents.canDelegate(profileId.trim())) {
            throw new IllegalArgumentException("unknown or non-execution sub-agent profile: " + profileId);
        }
        String normalizedProfileId = profileId.trim();
        String normalizedTask = task.trim();
        recordEvent(runId, "sub_agent_started", normalizedProfileId + " " + normalizedTask);
        try {
            if (runManager() != null && runId != null && runManager().subAgentDepth(runId) + 1 > options.maxDepth()) {
                throw new AgentBudgetExceededException("maximum sub-agent depth exceeded: " + options.maxDepth());
            }
            AgentRunResult child = subAgents.runForExecution(normalizedTask, apiKey, normalizedProfileId,
                    runId, null, null);
            budget.check();
            if (child.pendingApproval() != null) {
                recordEvent(runId, "sub_agent_waiting_approval", normalizedProfileId + " " + child.runId());
                throw new SubAgentApprovalRequiredException(child);
            }
            String result = delegationResult(normalizedProfileId, child);
            recordEvent(runId, "sub_agent_completed", normalizedProfileId + " " + result);
            return result;
        } catch (SubAgentApprovalRequiredException exception) {
            throw exception;
        } catch (Exception exception) {
            recordEvent(runId, "sub_agent_failed", normalizedProfileId + " " + exception.getMessage());
            throw exception;
        }
    }

    private static void checkToolPermission(String toolName, RunOptions options) {
        if (options.permissions() == null || options.permissions().isEmpty()) return;
        String decision = options.permissions().get("tool." + toolName);
        if (decision == null) decision = options.permissions().get(toolName);
        if (decision == null) decision = options.permissions().get("tool.*");
        if (decision == null) decision = options.permissions().get("*");
        if (decision != null && ("deny".equalsIgnoreCase(decision)
                || "false".equalsIgnoreCase(decision) || "disabled".equalsIgnoreCase(decision))) {
            throw new IllegalStateException("tool permission denied: " + toolName);
        }
    }

    private String delegationResult(JsonNode arguments, AgentRunResult child) {
        String profileId = arguments == null ? "unknown" : arguments.path("profileId").asString("unknown");
        return delegationResult(profileId, child);
    }

    private String delegationResult(String profileId, AgentRunResult child) {
        String answer = child.answer() == null ? "" : child.answer();
        String runLabel = child.runId() == null ? "" : " (run " + child.runId() + ")";
        return "Sub-agent " + profileId + " completed" + runLabel + ":\n" + answer;
    }

    private RunOptions options(String modelId, String agentId, AgentMode modeOverride) {
        return options(modelId, agentId, modeOverride, null, null);
    }

    private RunOptions options(String modelId, String agentId, AgentMode modeOverride,
                               String memoryNamespace, String memorySubjectKey) {
        AgentProfileRegistry profileRegistry = profileRegistry();
        if (profileRegistry == null) {
            return new RunOptions(blankToNull(modelId), modeOverride == null ? AgentMode.CHAT : modeOverride,
                    maxTurns, "", null, null, 64, 300, 4, memoryNamespace, memorySubjectKey, agentId, Map.of());
        }
        AgentProfileData profile = profileRegistry.resolve(agentId);
        return new RunOptions(blankToNull(modelId) == null ? profile.modelId() : blankToNull(modelId),
                modeOverride == null ? profile.mode() : modeOverride, profile.maxTurns(), profile.systemPrompt(),
                profile.allowedToolNames().isEmpty() ? null : Set.copyOf(profile.allowedToolNames()),
                profile.skillIds().isEmpty() ? null : Set.copyOf(profile.skillIds()), profile.maxToolCalls(),
                profile.timeoutSeconds(), profile.maxDepth(), memoryNamespace, memorySubjectKey, agentId, profile.permissions());
    }

    private RunOptions effectiveOptions(RunOptions options) {
        if (options == null || runtimeScope == null) return options;
        RuntimeProfile profile = runtimeScope.profile();
        if (profile == null) return options;
        String modelId = options.modelId() == null ? profile.modelId() : options.modelId();
        String systemPrompt = options.systemPrompt() == null || options.systemPrompt().isBlank()
                ? profile.systemPrompt() : options.systemPrompt();
        Set<String> allowedTools = options.allowedToolNames() == null && !profile.allowedToolNames().isEmpty()
                ? profile.allowedToolNames() : options.allowedToolNames();
        Set<String> skillIds = options.skillIds() == null && !profile.allowedSkillIds().isEmpty()
                ? profile.allowedSkillIds() : options.skillIds();
        Map<String, String> permissions = options.permissions().isEmpty() && !profile.permissions().isEmpty()
                ? profile.permissions() : options.permissions();
        String agentId = options.agentId() == null ? profile.id() : options.agentId();
        return new RunOptions(modelId, options.mode(), options.maxTurns(), systemPrompt, allowedTools, skillIds,
                options.maxToolCalls(), options.timeoutSeconds(), options.maxDepth(), options.memoryNamespace(),
                options.memorySubjectKey(), agentId, permissions);
    }

    private String systemPrompt(RunOptions options, String query, AgentRunContext context) throws Exception {
        SkillRegistry skillRegistry = skillRegistry();
        String base = skillRegistry == null ? "You are a helpful assistant. Use available tools when they are useful, then give a concise final answer."
                : skillRegistry.systemPrompt(options.skillIds());
        String modeInstruction = switch (options.mode()) {
            case CHAT -> "Stay conversational and use tools only when they help answer the request.";
            case PLANNING -> "You are in planning mode. Do not execute tools. Produce a clear, ordered plan with assumptions, dependencies, and verification steps.";
            case EXECUTION -> "You are in execution mode. Carry out the requested plan with available tools, verify important results, and report what was completed.";
        };
        String custom = options.systemPrompt();
        String result = custom == null || custom.isBlank() ? base + "\n\n" + modeInstruction
                : base + "\n\n" + modeInstruction + "\n\nProfile instructions:\n" + custom;
        MemoryManager memoryManager = memoryManager();
        if (memoryManager != null && options.memoryNamespace() != null && options.memorySubjectKey() != null) {
            String memoryContext = memoryManager.context(options.memoryNamespace(), options.memorySubjectKey(), query, 5);
            if (!memoryContext.isBlank()) result += "\n\n" + memoryContext;
        }
        ContextManager contextManager = contextManager();
        if (contextManager != null) {
            ChatModel activeModel = model();
            ContextSnapshot snapshot = contextManager.collect(new ContextRequest(
                    context == null ? null : context.conversationId(), query, options.modelId(),
                    options.agentId(), options.mode().name()), activeModel.contextWindow(options.modelId()),
                    activeModel.tokenizer(options.modelId()));
            String dynamic = snapshot.promptText();
            if (!dynamic.isBlank()) result += "\n\n" + dynamic;
        }
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String beginRun(RunOptions options, AgentRunContext context) throws Exception {
        RunManager runManager = runManager();
        if (runManager == null) return null;
        AgentRunContext actual = context == null ? AgentRunContext.standalone() : context;
        if (actual.kind() == io.github.git13166956007.dsh.run.RunKind.SUB_AGENT) {
            int depth = runManager.subAgentDepth(actual.parentRunId()) + 1;
            if (depth > options.maxDepth()) {
                throw new AgentBudgetExceededException("maximum sub-agent depth exceeded: " + options.maxDepth());
            }
        }
        return runManager.start(new RunSpec(actual.parentRunId(), actual.kind(), actual.conversationId(), actual.planId(),
                actual.stepId(), actual.agentId() == null ? options.agentId() : actual.agentId(), options.modelId()));
    }

    private void finishRun(String runId, String answer) throws Exception {
        RunManager runManager = runManager();
        if (runId != null && runManager != null) {
            runManager.complete(runId, answer);
            deleteContinuation(runId);
        }
        else if (runId != null) deleteContinuation(runId);
    }

    private void failRun(String runId, Exception exception) {
        if (runId == null) return;
        try {
            RunManager runManager = runManager();
            if (runManager != null) runManager.fail(runId, exception.getMessage());
            deleteContinuation(runId);
        } catch (Exception auditFailure) {
            exception.addSuppressed(auditFailure);
        }
    }

    private void cancelRun(String runId) {
        RunManager runManager = runManager();
        if (runId == null || runManager == null) return;
        try {
            io.github.git13166956007.dsh.run.Run run = runManager.find(runId);
            if (run != null && !run.status().terminal()) runManager.cancel(runId);
        } catch (Exception exception) {
            // Cancellation is best effort after the execution thread has been interrupted.
        }
    }

    private void recordEvent(String runId, String type, String payload) throws Exception {
        RunManager runManager = runManager();
        if (runId != null && runManager != null) runManager.event(runId, type, payload);
    }

    private void recordEventUnchecked(String runId, String type, String payload) {
        try {
            recordEvent(runId, type, payload);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to persist run event", exception);
        }
    }

    private void persistContinuation(PendingExecution pending) throws Exception {
        AgentContinuationStore continuations = continuationStore();
        if (continuations != null) continuations.save(pending.runId, serializeContinuation(pending));
    }

    private void persistAsyncRequest(String runId, String prompt, List<ChatMessage> history,
                                     RunOptions options) throws Exception {
        AgentContinuationStore continuations = continuationStore();
        if (continuations == null) return;
        ObjectNode root = objectMapper.createObjectNode();
        root.put("kind", "async_request");
        root.put("prompt", prompt);
        root.set("options", writeOptions(options));
        root.set("history", writeMessages(history == null ? List.of() : history));
        continuations.save(runId, objectMapper.writeValueAsString(root));
    }

    AsyncRequest readAsyncRequest(String payload) throws Exception {
        if (payload == null || payload.isBlank()) return null;
        JsonNode root = objectMapper.readTree(payload);
        if (root == null || !"async_request".equals(root.path("kind").asString(null))) return null;
        return new AsyncRequest(root.path("prompt").asString(null), readMessages(root.path("history")),
                readOptions(root.path("options")));
    }

    private PendingExecution restoreContinuation(String runId) throws Exception {
        AgentContinuationStore continuations = continuationStore();
        if (continuations == null) return null;
        String payload = continuations.load(runId);
        if (payload == null || payload.isBlank()) return null;
        JsonNode root = objectMapper.readTree(payload);
        RunOptions options = readOptions(root.path("options"));
        List<ChatMessage> messages = readMessages(root.path("messages"));
        List<AgentTraceEvent> trace = readTrace(root.path("trace"));
        PendingToolApproval approval = readApproval(root.path("approval"));
        List<ToolDefinition> definitions = definitions(options);
        ExecutionBudget budget = new ExecutionBudget(options, root.path("toolCalls").asInt(0),
                root.path("remainingMillis").asLong(Long.MAX_VALUE));
        return new PendingExecution(runId, messages, trace, root.path("nextTurn").asInt(0), options, null,
                definitions, budget, approval);
    }

    private String serializeContinuation(PendingExecution pending) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("nextTurn", pending.nextTurn);
        root.put("toolCalls", pending.budget.toolCalls);
        root.put("remainingMillis", pending.budget.remainingMillis());
        root.set("options", writeOptions(pending.options));
        root.set("messages", writeMessages(pending.messages));
        root.set("trace", writeTrace(pending.trace));
        root.set("approval", writeApproval(pending.approval));
        return objectMapper.writeValueAsString(root);
    }

    private ObjectNode writeOptions(RunOptions options) {
        ObjectNode node = objectMapper.createObjectNode();
        putNullable(node, "modelId", options.modelId());
        node.put("mode", options.mode().name());
        node.put("systemPrompt", options.systemPrompt());
        node.put("maxTurns", options.maxTurns());
        node.put("maxToolCalls", options.maxToolCalls());
        node.put("timeoutSeconds", options.timeoutSeconds());
        node.put("maxDepth", options.maxDepth());
        ObjectNode permissions = node.putObject("permissions");
        options.permissions().forEach(permissions::put);
        putNullable(node, "memoryNamespace", options.memoryNamespace());
        putNullable(node, "memorySubjectKey", options.memorySubjectKey());
        putNullable(node, "agentId", options.agentId());
        writeSet(node, "allowedToolNames", options.allowedToolNames());
        writeSet(node, "skillIds", options.skillIds());
        return node;
    }

    private RunOptions readOptions(JsonNode node) {
        String modelId = node.path("modelId").asString(null);
        AgentMode mode = AgentMode.parse(node.path("mode").asString(AgentMode.CHAT.name()));
        return new RunOptions(modelId, mode, node.path("maxTurns").asInt(maxTurns),
                node.path("systemPrompt").asString(""), readSet(node.path("allowedToolNames")),
                readSet(node.path("skillIds")), node.path("maxToolCalls").asInt(64),
                node.path("timeoutSeconds").asInt(300), node.path("maxDepth").asInt(4),
                node.path("memoryNamespace").asString(null), node.path("memorySubjectKey").asString(null),
                node.path("agentId").asString(null), readMap(node.path("permissions")));
    }

    private ArrayNode writeMessages(List<ChatMessage> messages) {
        ArrayNode array = objectMapper.createArrayNode();
        for (ChatMessage message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.role().value());
            putNullable(node, "content", message.content());
            putNullable(node, "reasoningContent", message.reasoningContent());
            putNullable(node, "toolCallId", message.toolCallId());
            ArrayNode calls = node.putArray("toolCalls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode item = calls.addObject();
                putNullable(item, "id", call.id());
                putNullable(item, "name", call.name());
                item.set("arguments", call.arguments() == null ? objectMapper.createObjectNode() : call.arguments().deepCopy());
            }
        }
        return array;
    }

    private List<ChatMessage> readMessages(JsonNode array) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        for (JsonNode node : array) {
            String role = node.path("role").asString("user");
            String content = node.path("content").asString(null);
            String reasoningContent = node.path("reasoningContent").asString(null);
            switch (role) {
                case "system" -> messages.add(ChatMessage.system(content));
                case "assistant" -> messages.add(ChatMessage.assistant(content, readToolCalls(node.path("toolCalls")), reasoningContent));
                case "tool" -> messages.add(ChatMessage.tool(node.path("toolCallId").asString(null), content));
                default -> messages.add(ChatMessage.user(content));
            }
        }
        return messages;
    }

    private List<ToolCall> readToolCalls(JsonNode array) {
        List<ToolCall> calls = new ArrayList<ToolCall>();
        for (JsonNode node : array) {
            calls.add(new ToolCall(node.path("id").asString(null), node.path("name").asString(null),
                    node.path("arguments").deepCopy()));
        }
        return calls;
    }

    private ArrayNode writeTrace(List<AgentTraceEvent> trace) {
        ArrayNode array = objectMapper.createArrayNode();
        for (AgentTraceEvent event : trace) {
            ObjectNode node = array.addObject();
            putNullable(node, "type", event.type());
            putNullable(node, "name", event.name());
            putNullable(node, "content", event.content());
            putNullable(node, "reasoningContent", event.reasoningContent());
            putNullable(node, "result", event.result());
            putNullable(node, "promptTokens", event.promptTokens());
            putNullable(node, "completionTokens", event.completionTokens());
            putNullable(node, "totalTokens", event.totalTokens());
            if (event.arguments() != null) node.set("arguments", event.arguments().deepCopy());
        }
        return array;
    }

    private List<AgentTraceEvent> readTrace(JsonNode array) {
        List<AgentTraceEvent> trace = new ArrayList<AgentTraceEvent>();
        for (JsonNode node : array) {
            trace.add(new AgentTraceEvent(node.path("type").asString(null), node.path("name").asString(null),
                    node.has("arguments") ? node.path("arguments").deepCopy() : null,
                    node.path("content").asString(null), node.path("result").asString(null),
                    integerValue(node, "promptTokens"), integerValue(node, "completionTokens"),
                    integerValue(node, "totalTokens"), node.path("reasoningContent").asString(null)));
        }
        return trace;
    }

    private static Integer integerValue(JsonNode node, String field) {
        return node.has(field) && node.path(field).isIntegralNumber() ? node.path(field).asInt() : null;
    }

    private ObjectNode writeApproval(PendingToolApproval approval) {
        ObjectNode node = objectMapper.createObjectNode();
        putNullable(node, "toolCallId", approval.toolCallId());
        putNullable(node, "toolName", approval.toolName());
        putNullable(node, "delegatedRunId", approval.delegatedRunId());
        node.set("arguments", approval.arguments() == null ? objectMapper.createObjectNode() : approval.arguments().deepCopy());
        return node;
    }

    private PendingToolApproval readApproval(JsonNode node) {
        return new PendingToolApproval(node.path("toolCallId").asString(null), node.path("toolName").asString(null),
                node.path("arguments").deepCopy(), node.path("delegatedRunId").asString(null));
    }

    private static void putNullable(ObjectNode node, String name, String value) {
        if (value == null) node.putNull(name);
        else node.put(name, value);
    }

    private static void putNullable(ObjectNode node, String name, Integer value) {
        if (value == null) node.putNull(name);
        else node.put(name, value);
    }

    private static void writeSet(ObjectNode node, String name, java.util.Set<String> values) {
        if (values == null) {
            node.putNull(name);
            return;
        }
        ArrayNode array = node.putArray(name);
        values.forEach(array::add);
    }

    record AsyncRequest(String prompt, List<ChatMessage> history, AgentLoop.RunOptions options) {
    }

    private static java.util.Set<String> readSet(JsonNode array) {
        if (array == null || array.isNull() || array.isMissingNode()) return null;
        java.util.Set<String> result = new java.util.LinkedHashSet<String>();
        for (JsonNode value : array) {
            String item = value.asString(null);
            if (item != null) result.add(item);
        }
        return result;
    }

    private static Map<String, String> readMap(JsonNode object) {
        if (object == null || object.isNull() || object.isMissingNode() || !object.isObject()) return Map.of();
        Map<String, String> result = new java.util.LinkedHashMap<String, String>();
        object.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asString("")));
        return result;
    }

    private void deleteContinuation(String runId) throws Exception {
        AgentContinuationStore continuations = continuationStore();
        if (continuations != null && runId != null) continuations.delete(runId);
    }

    @Override
    public void close() {
        asyncExecutor.shutdownNow();
        activeRuns.clear();
        activeThreads.values().forEach(Thread::interrupt);
        activeThreads.clear();
    }

        record RunOptions(String modelId, AgentMode mode, int maxTurns, String systemPrompt,
                              java.util.Set<String> allowedToolNames, java.util.Set<String> skillIds,
                              int maxToolCalls, int timeoutSeconds, int maxDepth,
                              String memoryNamespace, String memorySubjectKey, String agentId,
                              Map<String, String> permissions) {
        public RunOptions {
            permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
        }
    }

    private static final class PendingExecution {
        private final String runId;
        private final List<ChatMessage> messages;
        private final List<AgentTraceEvent> trace;
        private final int nextTurn;
        private final RunOptions options;
        private String apiKey;
        private final List<io.github.git13166956007.dsh.tool.ToolDefinition> definitions;
        private final ExecutionBudget budget;
        private final PendingToolApproval approval;

        private PendingExecution(String runId, List<ChatMessage> messages, List<AgentTraceEvent> trace, int nextTurn,
                                 RunOptions options, String apiKey,
                                 List<io.github.git13166956007.dsh.tool.ToolDefinition> definitions,
                                 ExecutionBudget budget, PendingToolApproval approval) {
            this.runId = runId;
            this.messages = messages;
            this.trace = trace;
            this.nextTurn = nextTurn;
            this.options = options;
            this.apiKey = apiKey;
            this.definitions = definitions;
            this.budget = budget;
            this.approval = approval;
        }
    }

    private static final class ExecutionBudget {
        private final int maxToolCalls;
        private final long deadlineNanos;
        private int toolCalls;

        private ExecutionBudget(RunOptions options) {
            this.maxToolCalls = options.maxToolCalls();
            this.deadlineNanos = options.timeoutSeconds() == 0 ? Long.MAX_VALUE
                    : System.nanoTime() + options.timeoutSeconds() * 1_000_000_000L;
        }

        private ExecutionBudget(RunOptions options, int toolCalls, long remainingMillis) {
            this.maxToolCalls = options.maxToolCalls();
            this.toolCalls = Math.max(0, toolCalls);
            this.deadlineNanos = remainingMillis == Long.MAX_VALUE ? Long.MAX_VALUE
                    : System.nanoTime() + Math.max(0, remainingMillis) * 1_000_000L;
        }

        private long remainingMillis() {
            return deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE
                    : Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000L);
        }

        private void check() {
            if (System.nanoTime() > deadlineNanos) {
                throw new AgentBudgetExceededException("agent timeout exceeded");
            }
        }

        private void beforeToolCall() {
            check();
            if (toolCalls >= maxToolCalls) {
                throw new AgentBudgetExceededException("maximum tool calls exceeded: " + maxToolCalls);
            }
            toolCalls++;
        }
    }
}
