package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.tool.ToolApprovalRequiredException;
import io.github.git13166956007.dsh.skill.SkillRegistry;
import io.github.git13166956007.dsh.memory.MemoryManager;
import io.github.git13166956007.dsh.run.Run;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.run.RunSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final ChatModel model;
    private final ToolRegistry tools;
    private final SkillRegistry skills;
    private final AgentProfileRegistry profiles;
    private final MemoryManager memories;
    private final RunManager runs;
    private final AgentContinuationStore continuations;
    private final ObjectMapper objectMapper;
    private final int maxTurns;
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool();
    private volatile SubAgentRunner subAgents;
    private final Map<String, PendingExecution> pendingApprovals = new ConcurrentHashMap<String, PendingExecution>();
    private final Map<String, Future<?>> activeRuns = new ConcurrentHashMap<String, Future<?>>();
    private final Map<String, Thread> activeThreads = new ConcurrentHashMap<String, Thread>();
    private final java.util.Set<String> cancelledRuns = ConcurrentHashMap.newKeySet();

    public AgentLoop(ChatModel model, ToolRegistry tools, int maxTurns) {
        this(model, tools, null, null, null, null, null, null, maxTurns);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, SkillRegistry skills, int maxTurns) {
        this(model, tools, skills, null, null, null, null, null, maxTurns);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, SkillRegistry skills,
                     AgentProfileRegistry profiles, int maxTurns) {
        this(model, tools, skills, profiles, null, null, null, null, maxTurns);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, SkillRegistry skills,
                     AgentProfileRegistry profiles, MemoryManager memories, int maxTurns) {
        this(model, tools, skills, profiles, memories, null, null, null, maxTurns);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, SkillRegistry skills,
                     AgentProfileRegistry profiles, MemoryManager memories, RunManager runs, int maxTurns) {
        this(model, tools, skills, profiles, memories, runs, null, null, maxTurns);
    }

    public AgentLoop(ChatModel model, ToolRegistry tools, SkillRegistry skills,
                     AgentProfileRegistry profiles, MemoryManager memories, RunManager runs,
                     AgentContinuationStore continuations, ObjectMapper objectMapper, int maxTurns) {
        if (maxTurns < 1) throw new IllegalArgumentException("maxTurns must be positive");
        this.model = model;
        this.tools = tools;
        this.skills = skills;
        this.profiles = profiles;
        this.memories = memories;
        this.runs = runs;
        this.continuations = continuations;
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
                executionOptions.maxDepth(), null, null, null), AgentRunContext.standalone());
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
        if (runs == null) throw new IllegalStateException("async agent runs require a RunManager");
        RunOptions options = runOptions(executionOptions, context);
        validatePrompt(prompt);
        String runId = beginRun(options, context);
        return submitAsync(runId, prompt, apiKey, history, options, context);
    }

    /** Reattaches an async execution to an existing durable Run after a restart. */
    public AgentRunHandle resumeAsync(String runId, String prompt, String apiKey, List<ChatMessage> history,
                                      AgentExecutionOptions executionOptions, AgentRunContext context) throws Exception {
        if (executionOptions == null) throw new IllegalArgumentException("executionOptions must not be null");
        if (runs == null) throw new IllegalStateException("async agent runs require a RunManager");
        Run run = runs.find(runId);
        if (run == null) throw new IllegalArgumentException("unknown run: " + runId);
        if (run.status() != io.github.git13166956007.dsh.run.RunStatus.RUNNING) {
            throw new IllegalStateException("run is not recoverable: " + runId);
        }
        RunOptions options = runOptions(executionOptions, context);
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
        String runId = beginRun(options, context);
        return runResolved(prompt, apiKey, history, options, context, runId);
    }

    private AgentRunResult runResolved(String prompt, String apiKey, List<ChatMessage> history,
                                       RunOptions options, AgentRunContext context, String runId) throws Exception {
        if (runId != null) activeThreads.put(runId, Thread.currentThread());
        try {
            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            List<AgentTraceEvent> trace = new ArrayList<AgentTraceEvent>();
            ExecutionBudget budget = new ExecutionBudget(options);
            messages.add(ChatMessage.system(systemPrompt(options, prompt)));
            messages.addAll(history);
            messages.add(ChatMessage.user(prompt));
            List<ToolDefinition> definitions = definitions(options);

            for (int turn = 0; turn < options.maxTurns(); turn++) {
                budget.check();
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
                    budget.beforeToolCall();
                    recordEvent(runId, "tool_call", call.name() + " " + call.arguments());
                    String result;
                    try {
                        result = executeTool(call, options, apiKey, runId, budget);
                    } catch (SubAgentApprovalRequiredException exception) {
                        PendingToolApproval approval = new PendingToolApproval(call.id(), call.name(), call.arguments(),
                                exception.pendingResult().runId());
                        String approvalRunId = pauseForApproval(runId, messages, trace, turn + 1, options, apiKey,
                                definitions, budget, approval);
                        return new AgentRunResult("", trace, turn + 1, approvalRunId, approval);
                    } catch (ToolApprovalRequiredException exception) {
                        PendingToolApproval approval = new PendingToolApproval(call.id(), call.name(), call.arguments());
                        String approvalRunId = pauseForApproval(runId, messages, trace, turn + 1, options, apiKey,
                                definitions, budget, approval);
                        return new AgentRunResult("", trace, turn + 1, approvalRunId, approval);
                    } catch (Exception exception) {
                        result = "Tool execution failed: " + exception.getMessage();
                    }
                    recordEvent(runId, "tool_result", call.name() + " " + result);
                    trace.add(AgentTraceEvent.tool(call.name(), call.arguments(), result));
                    messages.add(ChatMessage.tool(call.id(), result));
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
            throw exception;
        } finally {
            if (runId != null) activeThreads.remove(runId, Thread.currentThread());
        }
    }

    private static void validatePrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
    }

    private static RunOptions runOptions(AgentExecutionOptions options, AgentRunContext context) {
        return new RunOptions(options.modelId(), options.mode(), options.maxTurns(), options.systemPrompt(),
                options.allowedToolNames(), options.skillIds(), options.maxToolCalls(), options.timeoutSeconds(),
                options.maxDepth(), null, null, context == null ? null : context.agentId());
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
        if (runId != null) activeThreads.put(runId, Thread.currentThread());
        try {
            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            List<AgentTraceEvent> trace = new ArrayList<AgentTraceEvent>();
            ExecutionBudget budget = new ExecutionBudget(options);
            messages.add(ChatMessage.system(systemPrompt(options, prompt)));
            messages.addAll(history);
            messages.add(ChatMessage.user(prompt));
            List<ToolDefinition> definitions = definitions(options);

            for (int turn = 0; turn < options.maxTurns(); turn++) {
                budget.check();
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
                    budget.beforeToolCall();
                    recordEvent(runId, "tool_call", call.name() + " " + call.arguments());
                    listener.onToolCall(call);
                    String result;
                    try {
                        result = executeTool(call, options, apiKey, runId, budget);
                    } catch (SubAgentApprovalRequiredException exception) {
                        PendingToolApproval approval = new PendingToolApproval(call.id(), call.name(), call.arguments(),
                                exception.pendingResult().runId());
                        String approvalRunId = pauseForApproval(runId, messages, trace, turn + 1, options, apiKey,
                                definitions, budget, approval);
                        return new AgentRunResult("", trace, turn + 1, approvalRunId, approval);
                    } catch (ToolApprovalRequiredException exception) {
                        PendingToolApproval approval = new PendingToolApproval(call.id(), call.name(), call.arguments());
                        String approvalRunId = pauseForApproval(runId, messages, trace, turn + 1, options, apiKey,
                                definitions, budget, approval);
                        return new AgentRunResult("", trace, turn + 1, approvalRunId, approval);
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
            throw exception;
        } finally {
            if (runId != null) activeThreads.remove(runId, Thread.currentThread());
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
            if (runs != null) {
                if (runs.find(runId) == null) throw new IllegalArgumentException("unknown run: " + runId);
                runs.resume(runId);
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
                try {
                    result = tools.executeApproved(pending.approval.toolName(), pending.approval.arguments(),
                            pending.options.allowedToolNames());
                } catch (Exception exception) {
                    result = "Tool execution failed: " + exception.getMessage();
                }
            }
            recordEvent(runId, "tool_result", pending.approval.toolName() + " " + result);
            pending.trace.add(AgentTraceEvent.tool(pending.approval.toolName(), pending.approval.arguments(), result));
            pending.messages.add(ChatMessage.tool(pending.approval.toolCallId(), result));
            return continueDetailed(pending);
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

    private AgentRunResult continueDetailed(PendingExecution pending) throws Exception {
        for (int turn = pending.nextTurn; turn < pending.options.maxTurns(); turn++) {
            pending.budget.check();
            ModelResponse response = model.complete(pending.messages, pending.definitions, pending.apiKey,
                    pending.options.modelId());
            recordEvent(pending.runId, "model_response", response.content());
            pending.messages.add(ChatMessage.assistant(response.content(), response.toolCalls()));
            if (response.content() != null && !response.content().isEmpty()) {
                pending.trace.add(AgentTraceEvent.model(response.content()));
            }
            if (response.toolCalls().isEmpty()) {
                String answer = response.content() == null ? "" : response.content();
                finishRun(pending.runId, answer);
                return new AgentRunResult(answer, pending.trace, turn + 1, pending.runId);
            }
            for (ToolCall call : response.toolCalls()) {
                pending.budget.beforeToolCall();
                recordEvent(pending.runId, "tool_call", call.name() + " " + call.arguments());
                String result;
                try {
                    result = executeTool(call, pending.options, pending.apiKey, pending.runId, pending.budget);
                } catch (SubAgentApprovalRequiredException exception) {
                    PendingToolApproval approval = new PendingToolApproval(call.id(), call.name(), call.arguments(),
                            exception.pendingResult().runId());
                    pauseForApproval(pending.runId, pending.messages, pending.trace, turn + 1, pending.options,
                            pending.apiKey, pending.definitions, pending.budget, approval);
                    return new AgentRunResult("", pending.trace, turn + 1, pending.runId, approval);
                } catch (ToolApprovalRequiredException exception) {
                    PendingToolApproval approval = new PendingToolApproval(call.id(), call.name(), call.arguments());
                    pauseForApproval(pending.runId, pending.messages, pending.trace, turn + 1, pending.options,
                            pending.apiKey, pending.definitions, pending.budget, approval);
                    return new AgentRunResult("", pending.trace, turn + 1, pending.runId, approval);
                } catch (Exception exception) {
                    result = "Tool execution failed: " + exception.getMessage();
                }
                recordEvent(pending.runId, "tool_result", call.name() + " " + result);
                pending.trace.add(AgentTraceEvent.tool(call.name(), call.arguments(), result));
                pending.messages.add(ChatMessage.tool(call.id(), result));
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
        if (runs != null) {
            runs.waitForApproval(actualRunId, approval.toolName() + " " + approval.arguments());
        }
        return actualRunId;
    }

    private List<ToolDefinition> definitions(RunOptions options) {
        if (!options.mode().toolsEnabled()) return List.of();
        List<ToolDefinition> result = new ArrayList<ToolDefinition>(tools.definitions(options.allowedToolNames()));
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
        if (!DELEGATE_TOOL.equals(call.name())) {
            return tools.execute(call.name(), call.arguments(), options.allowedToolNames());
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
            if (runs != null && runId != null && runs.subAgentDepth(runId) + 1 > options.maxDepth()) {
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
        if (profiles == null) {
            return new RunOptions(blankToNull(modelId), modeOverride == null ? AgentMode.CHAT : modeOverride,
                    maxTurns, "", null, null, 64, 300, 4, memoryNamespace, memorySubjectKey, agentId);
        }
        AgentProfileData profile = profiles.resolve(agentId);
        return new RunOptions(blankToNull(modelId) == null ? profile.modelId() : blankToNull(modelId),
                modeOverride == null ? profile.mode() : modeOverride, profile.maxTurns(), profile.systemPrompt(), null, null,
                profile.maxToolCalls(), profile.timeoutSeconds(), profile.maxDepth(), memoryNamespace, memorySubjectKey, agentId);
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
        if (actual.kind() == io.github.git13166956007.dsh.run.RunKind.SUB_AGENT) {
            int depth = runs.subAgentDepth(actual.parentRunId()) + 1;
            if (depth > options.maxDepth()) {
                throw new AgentBudgetExceededException("maximum sub-agent depth exceeded: " + options.maxDepth());
            }
        }
        return runs.start(new RunSpec(actual.parentRunId(), actual.kind(), actual.conversationId(), actual.planId(),
                actual.stepId(), actual.agentId() == null ? options.agentId() : actual.agentId(), options.modelId()));
    }

    private void finishRun(String runId, String answer) throws Exception {
        if (runId != null && runs != null) {
            runs.complete(runId, answer);
            deleteContinuation(runId);
        }
        else if (runId != null) deleteContinuation(runId);
    }

    private void failRun(String runId, Exception exception) {
        if (runId == null) return;
        try {
            if (runs != null) runs.fail(runId, exception.getMessage());
            deleteContinuation(runId);
        } catch (Exception auditFailure) {
            exception.addSuppressed(auditFailure);
        }
    }

    private void cancelRun(String runId) {
        if (runId == null || runs == null) return;
        try {
            io.github.git13166956007.dsh.run.Run run = runs.find(runId);
            if (run != null && !run.status().terminal()) runs.cancel(runId);
        } catch (Exception exception) {
            // Cancellation is best effort after the execution thread has been interrupted.
        }
    }

    private void recordEvent(String runId, String type, String payload) throws Exception {
        if (runId != null && runs != null) runs.event(runId, type, payload);
    }

    private void recordEventUnchecked(String runId, String type, String payload) {
        try {
            recordEvent(runId, type, payload);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to persist run event", exception);
        }
    }

    private void persistContinuation(PendingExecution pending) throws Exception {
        if (continuations != null) continuations.save(pending.runId, serializeContinuation(pending));
    }

    private void persistAsyncRequest(String runId, String prompt, List<ChatMessage> history,
                                     RunOptions options) throws Exception {
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
                node.path("agentId").asString(null));
    }

    private ArrayNode writeMessages(List<ChatMessage> messages) {
        ArrayNode array = objectMapper.createArrayNode();
        for (ChatMessage message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.role().value());
            putNullable(node, "content", message.content());
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
            switch (role) {
                case "system" -> messages.add(ChatMessage.system(content));
                case "assistant" -> messages.add(ChatMessage.assistant(content, readToolCalls(node.path("toolCalls"))));
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
            putNullable(node, "result", event.result());
            if (event.arguments() != null) node.set("arguments", event.arguments().deepCopy());
        }
        return array;
    }

    private List<AgentTraceEvent> readTrace(JsonNode array) {
        List<AgentTraceEvent> trace = new ArrayList<AgentTraceEvent>();
        for (JsonNode node : array) {
            trace.add(new AgentTraceEvent(node.path("type").asString(null), node.path("name").asString(null),
                    node.has("arguments") ? node.path("arguments").deepCopy() : null,
                    node.path("content").asString(null), node.path("result").asString(null)));
        }
        return trace;
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

    private void deleteContinuation(String runId) throws Exception {
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
                              String memoryNamespace, String memorySubjectKey, String agentId) {
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
