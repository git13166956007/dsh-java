package io.github.git13166956007.dsh.web;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;
import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentMode;
import io.github.git13166956007.dsh.agent.AgentProfile;
import io.github.git13166956007.dsh.agent.AgentProfileRegistry;
import io.github.git13166956007.dsh.agent.SubAgentProfile;
import io.github.git13166956007.dsh.agent.SubAgentProfileRegistry;
import io.github.git13166956007.dsh.agent.AgentStreamListener;
import io.github.git13166956007.dsh.agent.AgentRunResult;
import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ToolCall;
import io.github.git13166956007.dsh.context.ContextManager;
import io.github.git13166956007.dsh.context.ContextWindow;
import io.github.git13166956007.dsh.mcp.McpServerInfo;
import io.github.git13166956007.dsh.mcp.McpServerRegistry;
import io.github.git13166956007.dsh.mcp.McpClientManager;
import io.github.git13166956007.dsh.memory.MemoryManager;
import io.github.git13166956007.dsh.memory.MemoryRecord;
import io.github.git13166956007.dsh.run.Run;
import io.github.git13166956007.dsh.run.RunEvent;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.skill.SkillInfo;
import io.github.git13166956007.dsh.skill.SkillRegistry;
import io.github.git13166956007.dsh.model.ModelProfile;
import io.github.git13166956007.dsh.model.ModelRegistry;
import io.github.git13166956007.dsh.plan.Plan;
import io.github.git13166956007.dsh.plan.AdaptivePlanService;
import io.github.git13166956007.dsh.plan.PlanExecutor;
import io.github.git13166956007.dsh.plan.PlanRegistry;
import io.github.git13166956007.dsh.tool.ToolInfo;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.core.DshRuntime;
import io.github.git13166956007.dsh.provider.deepseek.ModelConfigurationException;
import io.github.git13166956007.dsh.provider.deepseek.ModelQuotaException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public final class DshController {
    private final DshRuntime runtime;
    private final AgentLoop agentLoop;
    private final ContextManager contextManager;
    private final ToolRegistry toolRegistry;
    private final McpServerRegistry mcpServerRegistry;
    private final McpClientManager mcpClientManager;
    private final SkillRegistry skillRegistry;
    private final ModelRegistry modelRegistry;
    private final AgentProfileRegistry agentProfileRegistry;
    private final PlanRegistry planRegistry;
    private final PlanExecutor planExecutor;
    private final SubAgentProfileRegistry subAgentProfileRegistry;
    private final AdaptivePlanService adaptivePlanService;
    private final MemoryManager memoryManager;
    private final RunManager runManager;

    public DshController(DshRuntime runtime, AgentLoop agentLoop, ContextManager contextManager,
                         ToolRegistry toolRegistry, McpServerRegistry mcpServerRegistry,
                         McpClientManager mcpClientManager, SkillRegistry skillRegistry,
                         ModelRegistry modelRegistry, AgentProfileRegistry agentProfileRegistry,
                         PlanRegistry planRegistry, PlanExecutor planExecutor,
                         SubAgentProfileRegistry subAgentProfileRegistry, AdaptivePlanService adaptivePlanService,
                         MemoryManager memoryManager, RunManager runManager) {
        this.runtime = runtime;
        this.agentLoop = agentLoop;
        this.contextManager = contextManager;
        this.toolRegistry = toolRegistry;
        this.mcpServerRegistry = mcpServerRegistry;
        this.mcpClientManager = mcpClientManager;
        this.skillRegistry = skillRegistry;
        this.modelRegistry = modelRegistry;
        this.agentProfileRegistry = agentProfileRegistry;
        this.planRegistry = planRegistry;
        this.planExecutor = planExecutor;
        this.subAgentProfileRegistry = subAgentProfileRegistry;
        this.adaptivePlanService = adaptivePlanService;
        this.memoryManager = memoryManager;
        this.runManager = runManager;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", "dsh-java");
        result.put("runtimeStarted", runtime.isStarted());
        result.put("pluginCount", runtime.pluginCount());
        return result;
    }

    @GetMapping("/tools")
    public java.util.List<ToolInfo> tools() {
        return toolRegistry.list();
    }

    @PostMapping("/tools")
    public ToolInfo createTool(@RequestBody ToolCreateRequest request) {
        if (request == null || request.name() == null || request.description() == null
                || request.parameters() == null || request.result() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "name, description, parameters and result are required");
        }
        try {
            if (!request.parameters().isObject()) {
                throw new IllegalArgumentException("parameters must be a JSON object");
            }
            toolRegistry.registerCustom(new io.github.git13166956007.dsh.tool.ToolDefinition(
                    request.name().trim(), request.description().trim(),
                    (ObjectNode) request.parameters()), request.result(), Boolean.TRUE.equals(request.approvalRequired()));
            return toolRegistry.list().stream()
                    .filter(tool -> tool.name().equals(request.name().trim()))
                    .findFirst().orElseThrow();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PatchMapping("/tools/{name}")
    public ToolInfo updateTool(@PathVariable String name, @RequestBody ToolUpdateRequest request) {
        if (request == null || (request.enabled() == null && request.approvalRequired() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled or approvalRequired must be provided");
        }
        boolean found = request.enabled() == null || toolRegistry.setEnabled(name, request.enabled());
        found = request.approvalRequired() == null || toolRegistry.setApprovalRequired(name, request.approvalRequired());
        if (!found) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown tool: " + name);
        }
        return toolRegistry.list().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    @DeleteMapping("/tools/{name}")
    public void deleteTool(@PathVariable String name) {
        if (!toolRegistry.remove(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "tool does not exist or is not removable: " + name);
        }
    }

    @GetMapping("/mcp/servers")
    public java.util.List<McpServerInfo> mcpServers() {
        return mcpServerRegistry.list();
    }

    @PostMapping("/mcp/servers")
    public McpServerInfo createMcpServer(@RequestBody McpServerRequest request) {
        try {
            return mcpServerRegistry.create(request.name(), request.transport(), request.endpoint(),
                    request.command(), request.arguments());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PatchMapping("/mcp/servers/{id}")
    public McpServerInfo updateMcpServer(@PathVariable String id, @RequestBody McpServerRequest request) {
        try {
            mcpClientManager.disconnect(id);
            McpServerInfo updated = mcpServerRegistry.update(id, request.name(), request.transport(), request.endpoint(),
                    request.command(), request.arguments(), request.enabled());
            return updated.enabled() ? mcpClientManager.connect(id) : updated;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/mcp/servers/{id}")
    public void deleteMcpServer(@PathVariable String id) {
        if (mcpServerRegistry.find(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown MCP server: " + id);
        }
        mcpClientManager.remove(id);
    }

    @PostMapping("/mcp/servers/{id}/connect")
    public McpServerInfo connectMcpServer(@PathVariable String id) {
        try {
            return mcpClientManager.connect(id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage(), exception);
        }
    }

    @PostMapping("/mcp/servers/{id}/refresh")
    public McpServerInfo refreshMcpServer(@PathVariable String id) {
        try {
            return mcpClientManager.refresh(id);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage(), exception);
        }
    }

    @PostMapping("/mcp/servers/{id}/disconnect")
    public McpServerInfo disconnectMcpServer(@PathVariable String id) {
        try {
            return mcpClientManager.disconnect(id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @GetMapping("/skills")
    public java.util.List<SkillInfo> skills() {
        return skillRegistry.list();
    }

    @GetMapping("/skills/{id}")
    public SkillInfo skill(@PathVariable String id) {
        SkillInfo skill = skillRegistry.find(id);
        if (skill == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown skill: " + id);
        return skill;
    }

    @PostMapping("/skills/refresh")
    public java.util.List<SkillInfo> refreshSkills() {
        skillRegistry.refresh();
        return skillRegistry.list();
    }

    @PatchMapping("/skills/{id}")
    public SkillInfo updateSkill(@PathVariable String id, @RequestBody SkillUpdateRequest request) {
        if (request == null || request.enabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled must be provided");
        }
        try {
            return skillRegistry.setEnabled(id, request.enabled());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @GetMapping("/models")
    public java.util.List<ModelProfile> models() {
        return modelRegistry.list();
    }

    @PostMapping("/models")
    public ModelProfile createModel(@RequestBody ModelRequest request) {
        try {
            return modelRegistry.create(request.name(), request.provider(), request.baseUrl(), request.model(),
                    request.apiKey(), request.proxyHost(), request.proxyPort(), request.enabled(), request.active());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PatchMapping("/models/{id}")
    public ModelProfile updateModel(@PathVariable String id, @RequestBody ModelRequest request) {
        try {
            return modelRegistry.update(id, request.name(), request.provider(), request.baseUrl(), request.model(),
                    request.apiKey(), request.proxyHost(), request.proxyPort(), request.enabled(), request.active());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @PostMapping("/models/{id}/activate")
    public ModelProfile activateModel(@PathVariable String id) {
        try {
            return modelRegistry.activate(id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/models/{id}")
    public void deleteModel(@PathVariable String id) {
        try {
            if (!modelRegistry.delete(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown model: " + id);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), exception);
        }
    }

    @GetMapping("/agents")
    public java.util.List<AgentProfile> agents() {
        return agentProfileRegistry.list();
    }

    @PostMapping("/agents")
    public AgentProfile createAgent(@RequestBody AgentProfileRequest request) {
        try {
            return agentProfileRegistry.create(request.name(), AgentMode.parse(request.mode()), request.modelId(),
                    request.systemPrompt(), request.maxTurns(), request.enabled(), request.active());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PatchMapping("/agents/{id}")
    public AgentProfile updateAgent(@PathVariable String id, @RequestBody AgentProfileRequest request) {
        try {
            return agentProfileRegistry.update(id, request.name(), request.mode() == null ? null : AgentMode.parse(request.mode()),
                    request.modelId(), request.systemPrompt(), request.maxTurns(), request.enabled(), request.active());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/agents/{id}/activate")
    public AgentProfile activateAgent(@PathVariable String id) {
        try {
            return agentProfileRegistry.activate(id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/agents/{id}")
    public void deleteAgent(@PathVariable String id) {
        if (!agentProfileRegistry.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown agent profile: " + id);
        }
    }

    @GetMapping("/sub-agents")
    public java.util.List<SubAgentProfile> subAgents() {
        return subAgentProfileRegistry.list();
    }

    @PostMapping("/sub-agents")
    public SubAgentProfile createSubAgent(@RequestBody SubAgentProfileRequest request) {
        try {
            return subAgentProfileRegistry.create(request.name(), AgentMode.parse(request.mode()), request.modelId(),
                    request.systemPrompt(), request.maxTurns(), request.allowedToolNames(), request.skillIds(), request.enabled());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PatchMapping("/sub-agents/{id}")
    public SubAgentProfile updateSubAgent(@PathVariable String id, @RequestBody SubAgentProfileRequest request) {
        try {
            return subAgentProfileRegistry.update(id, request.name(), request.mode() == null ? null : AgentMode.parse(request.mode()),
                    request.modelId(), request.systemPrompt(), request.maxTurns(), request.allowedToolNames(),
                    request.skillIds(), request.enabled());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/sub-agents/{id}")
    public void deleteSubAgent(@PathVariable String id) {
        if (!subAgentProfileRegistry.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown sub-agent profile: " + id);
        }
    }

    @GetMapping("/memories")
    public java.util.List<MemoryRecord> memories(@RequestParam String namespace,
                                                 @RequestParam String subjectKey,
                                                 @RequestParam(defaultValue = "50") int limit) throws Exception {
        return memoryManager.list(namespace, subjectKey, limit);
    }

    @GetMapping("/conversations/{id}/context")
    public ContextResponse conversationContext(@PathVariable String id) throws Exception {
        ContextWindow window = contextManager.window(id);
        return new ContextResponse(id, window.messages().size(), window.estimatedTokens(), window.maxTokens(),
                window.truncated());
    }

    @GetMapping("/memories/search")
    public java.util.List<MemoryRecord> searchMemories(@RequestParam String namespace,
                                                       @RequestParam String subjectKey,
                                                       @RequestParam(defaultValue = "") String query,
                                                       @RequestParam(defaultValue = "20") int limit) throws Exception {
        return memoryManager.search(namespace, subjectKey, query, limit);
    }

    @PostMapping("/memories")
    public MemoryRecord createMemory(@RequestBody MemoryRequest request) throws Exception {
        try {
            return memoryManager.save(request.namespace(), request.subjectKey(), request.memoryType(), request.content(),
                    request.metadataJson(), request.importance());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/memories/{id}")
    public void deleteMemory(@PathVariable long id) throws Exception {
        memoryManager.delete(id);
    }

    @GetMapping("/runs")
    public java.util.List<Run> runs(@RequestParam(required = false) String conversationId,
                                    @RequestParam(required = false) String planId,
                                    @RequestParam(required = false) String parentRunId) throws Exception {
        return runManager.list().stream()
                .filter(run -> conversationId == null || conversationId.equals(run.conversationId()))
                .filter(run -> planId == null || planId.equals(run.planId()))
                .filter(run -> parentRunId == null || parentRunId.equals(run.parentRunId()))
                .toList();
    }

    @GetMapping("/runs/{id}")
    public Run run(@PathVariable String id) throws Exception {
        Run run = runManager.find(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown run: " + id);
        return run;
    }

    @GetMapping("/runs/{id}/events")
    public java.util.List<RunEvent> runEvents(@PathVariable String id) throws Exception {
        if (runManager.find(id) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown run: " + id);
        return runManager.events(id);
    }

    @GetMapping("/runs/{id}/tree")
    public java.util.List<Run> runTree(@PathVariable String id) throws Exception {
        if (runManager.find(id) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown run: " + id);
        java.util.List<Run> all = runManager.list();
        java.util.Map<String, Run> byId = all.stream().collect(java.util.stream.Collectors.toMap(Run::id, run -> run));
        return all.stream().filter(run -> isDescendant(run, id, byId)).toList();
    }

    @PostMapping("/runs/{id}/approval")
    public ChatResponse approveRun(@PathVariable String id, @RequestBody ApprovalRequest request) throws Exception {
        if (request == null || request.approved() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "approved must be provided");
        }
        Run run = runManager.find(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown run: " + id);
        if (run.status() != io.github.git13166956007.dsh.run.RunStatus.WAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "run is not awaiting approval: " + id);
        }
        try {
            AgentRunResult result = agentLoop.resumeApproval(id, request.approved());
            if (result.pendingApproval() == null && run.conversationId() != null) {
                contextManager.append(run.conversationId(), ChatMessage.assistant(result.answer(), java.util.List.of()));
            }
            return new ChatResponse(run.conversationId(), result.answer(), result.trace(), result.turns(),
                    result.runId(), result.pendingApproval());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    @GetMapping("/plans")
    public java.util.List<Plan> plans() {
        return planRegistry.list();
    }

    @GetMapping("/plans/{id}")
    public Plan plan(@PathVariable String id) {
        Plan plan = planRegistry.find(id);
        if (plan == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown plan: " + id);
        return plan;
    }

    @PostMapping("/plans")
    public Plan createPlan(@RequestBody PlanRequest request) {
        if (request == null || request.steps() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title, goal and steps are required");
        }
        try {
            java.util.List<io.github.git13166956007.dsh.plan.PlanRegistry.PlanStepInput> steps = request.steps().stream()
                    .map(step -> new io.github.git13166956007.dsh.plan.PlanRegistry.PlanStepInput(
                            step.title(), step.instruction(), step.maxAttempts(), step.subAgentId(), step.dependsOn()))
                    .toList();
            return planRegistry.create(request.title(), request.goal(), request.agentId(), request.modelId(),
                    request.approvalRequired() == null || request.approvalRequired(),
                    request.maxConcurrency() == null ? 1 : request.maxConcurrency(), steps);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/plans/adaptive")
    public Plan createAdaptivePlan(@RequestBody AdaptivePlanRequest request) throws Exception {
        if (request == null || request.prompt() == null || request.prompt().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt must not be blank");
        }
        try {
            return adaptivePlanService.create(request.prompt(), request.apiKey(), request.agentId(), request.modelId(),
                    request.approvalRequired() == null || request.approvalRequired(), request.maxSteps(), request.maxConcurrency(),
                    Boolean.TRUE.equals(request.allowDynamicSubAgents()));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/plans/{id}/approve")
    public Plan approvePlan(@PathVariable String id) {
        try {
            return planRegistry.approve(id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    @PostMapping("/plans/{id}/execute")
    public Plan executePlan(@PathVariable String id, @RequestBody(required = false) PlanExecuteRequest request) {
        try {
            return planExecutor.execute(id, request == null ? null : request.apiKey());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    @PostMapping("/plans/{id}/cancel")
    public Plan cancelPlan(@PathVariable String id) {
        try {
            return planExecutor.cancel(id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/plans/{id}")
    public void deletePlan(@PathVariable String id) {
        if (!planRegistry.delete(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown plan: " + id);
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) throws Exception {
        if (request == null || request.message() == null || request.message().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be blank");
        }
        AgentMode mode = requestedMode(request.mode());
        try {
            String conversationId = contextManager.open(request.conversationId(), request.message());
            java.util.List<ChatMessage> history = contextManager.history(conversationId);
            contextManager.append(conversationId, ChatMessage.user(request.message()));
            AgentRunResult result = agentLoop.runDetailed(request.message(), request.apiKey(), history, request.modelId(),
                    request.agentId(), mode, "conversation", conversationId,
                    io.github.git13166956007.dsh.agent.AgentRunContext.chat(conversationId, request.agentId()));
            if (result.pendingApproval() == null) {
                contextManager.append(conversationId, ChatMessage.assistant(result.answer(), java.util.List.of()));
            }
            return new ChatResponse(conversationId, result.answer(), result.trace(), result.turns(), result.runId(),
                    result.pendingApproval());
        } catch (Exception exception) {
            if (exception instanceof ModelQuotaException quotaException) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        quotaException.getMessage(), quotaException);
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), exception);
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        if (request == null || request.message() == null || request.message().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be blank");
        }

        String conversationId;
        java.util.List<ChatMessage> history;
        AgentMode mode = requestedMode(request.mode());
        try {
            conversationId = contextManager.open(request.conversationId(), request.message());
            history = contextManager.history(conversationId);
            contextManager.append(conversationId, ChatMessage.user(request.message()));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), exception);
        }

        SseEmitter emitter = new SseEmitter(180_000L);
        String finalConversationId = conversationId;
        java.util.List<ChatMessage> finalHistory = history;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                AgentRunResult result = agentLoop.runStreaming(request.message(), request.apiKey(), finalHistory,
                        request.modelId(), request.agentId(), mode, "conversation", finalConversationId,
                        io.github.git13166956007.dsh.agent.AgentRunContext.chat(finalConversationId, request.agentId()),
                        new AgentStreamListener() {
                    @Override
                    public void onText(String delta) {
                        send(emitter, "delta", delta);
                    }

                    @Override
                    public void onToolCall(ToolCall call) {
                        Map<String, Object> data = new LinkedHashMap<String, Object>();
                        data.put("id", call.id());
                        data.put("name", call.name());
                        data.put("arguments", call.arguments());
                        send(emitter, "tool_call", data);
                    }

                    @Override
                    public void onToolResult(io.github.git13166956007.dsh.agent.AgentTraceEvent result) {
                        send(emitter, "tool_result", result);
                    }
                });
                if (result.pendingApproval() != null) {
                    send(emitter, "approval_required", result.pendingApproval());
                } else {
                    contextManager.append(finalConversationId, ChatMessage.assistant(result.answer(), java.util.List.of()));
                }
                send(emitter, "done", new StreamResponse(finalConversationId, result.answer(), result.trace(), result.turns(),
                        result.runId(), result.pendingApproval()));
                emitter.complete();
            } catch (Exception exception) {
                send(emitter, "error", new ErrorResponse(exception.getMessage()));
                emitter.complete();
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception ignored) {
            emitter.completeWithError(ignored);
        }
    }

    private static AgentMode requestedMode(String value) {
        try {
            return value == null || value.trim().isEmpty() ? null : AgentMode.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private static boolean isDescendant(Run run, String rootId, java.util.Map<String, Run> byId) {
        java.util.Set<String> visited = new java.util.HashSet<String>();
        String current = run.id();
        while (current != null && visited.add(current)) {
            if (rootId.equals(current)) return true;
            Run candidate = byId.get(current);
            current = candidate == null ? null : candidate.parentRunId();
        }
        return false;
    }

    @ExceptionHandler(ModelConfigurationException.class)
    public ResponseEntity<ErrorResponse> modelConfigurationError(ModelConfigurationException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(ModelQuotaException.class)
    public ResponseEntity<ErrorResponse> modelQuotaError(ModelQuotaException exception) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(new ErrorResponse(exception.getMessage()));
    }

    public record ChatRequest(String message, String apiKey, String conversationId, String modelId,
                              String agentId, String mode) {
    }

    public record ToolUpdateRequest(Boolean enabled, Boolean approvalRequired) {
    }

    public record SkillUpdateRequest(Boolean enabled) {
    }

    public record ModelRequest(String name, String provider, String baseUrl, String model, String apiKey,
                               String proxyHost, Integer proxyPort, Boolean enabled, Boolean active) {
    }

    public record AgentProfileRequest(String name, String mode, String modelId, String systemPrompt,
                                      Integer maxTurns, Boolean enabled, Boolean active) {
    }

    public record SubAgentProfileRequest(String name, String mode, String modelId, String systemPrompt,
                                         Integer maxTurns, java.util.List<String> allowedToolNames,
                                         java.util.List<String> skillIds, Boolean enabled) {
    }

    public record PlanRequest(String title, String goal, String agentId, String modelId,
                               Boolean approvalRequired, Integer maxConcurrency, java.util.List<PlanStepRequest> steps) {
    }

    public record PlanStepRequest(String title, String instruction, Integer maxAttempts, String subAgentId,
                                  java.util.List<Integer> dependsOn) {
    }

    public record PlanExecuteRequest(String apiKey) {
    }

    public record AdaptivePlanRequest(String prompt, String apiKey, String agentId, String modelId,
                                      Boolean approvalRequired, Integer maxSteps, Integer maxConcurrency,
                                      Boolean allowDynamicSubAgents) {
    }

    public record MemoryRequest(String namespace, String subjectKey, String memoryType, String content,
                                String metadataJson, Double importance) {
    }

    public record ToolCreateRequest(String name, String description, tools.jackson.databind.JsonNode parameters,
                                    String result, Boolean approvalRequired) {
    }

    public record McpServerRequest(String name, String transport, String endpoint, String command,
                                   java.util.List<String> arguments, Boolean enabled) {
    }

    public record ChatResponse(String conversationId, String message,
                               java.util.List<io.github.git13166956007.dsh.agent.AgentTraceEvent> trace,
                               int turns, String runId,
                               io.github.git13166956007.dsh.agent.PendingToolApproval pendingApproval) {
    }

    public record StreamResponse(String conversationId, String answer,
                                 java.util.List<io.github.git13166956007.dsh.agent.AgentTraceEvent> trace,
                                 int turns, String runId,
                                 io.github.git13166956007.dsh.agent.PendingToolApproval pendingApproval) {
    }

    public record ApprovalRequest(Boolean approved) {
    }

    public record ErrorResponse(String error) {
    }

    public record ContextResponse(String conversationId, int messageCount, int estimatedTokens,
                                  int maxTokens, boolean truncated) {
    }
}
