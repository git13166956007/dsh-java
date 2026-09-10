package io.github.git13166956007.dsh.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentRunHandle;
import io.github.git13166956007.dsh.agent.AgentMode;
import io.github.git13166956007.dsh.agent.AgentProfile;
import io.github.git13166956007.dsh.agent.AgentProfileRegistry;
import io.github.git13166956007.dsh.agent.SubAgentProfile;
import io.github.git13166956007.dsh.agent.SubAgentProfileRegistry;
import io.github.git13166956007.dsh.agent.SubAgentRunner;
import io.github.git13166956007.dsh.agent.SubAgentSession;
import io.github.git13166956007.dsh.agent.SubAgentSessionManager;
import io.github.git13166956007.dsh.agent.AgentStreamListener;
import io.github.git13166956007.dsh.agent.AgentRunResult;
import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ToolCall;
import io.github.git13166956007.dsh.context.ContextManager;
import io.github.git13166956007.dsh.context.ContextWindow;
import io.github.git13166956007.dsh.mcp.McpServerInfo;
import io.github.git13166956007.dsh.mcp.McpServerRegistry;
import io.github.git13166956007.dsh.mcp.McpClientManager;
import io.github.git13166956007.dsh.mcp.McpPromptInfo;
import io.github.git13166956007.dsh.mcp.McpPromptResult;
import io.github.git13166956007.dsh.mcp.McpResourceContent;
import io.github.git13166956007.dsh.mcp.McpResourceInfo;
import io.github.git13166956007.dsh.mcp.McpResourceSubscription;
import io.github.git13166956007.dsh.mcp.McpResourceUpdate;
import io.github.git13166956007.dsh.mcp.McpHealth;
import io.github.git13166956007.dsh.memory.MemoryManager;
import io.github.git13166956007.dsh.memory.MemoryRecord;
import io.github.git13166956007.dsh.run.Run;
import io.github.git13166956007.dsh.run.RunEvent;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.skill.SkillInfo;
import io.github.git13166956007.dsh.skill.SkillRegistry;
import io.github.git13166956007.dsh.model.ModelProfile;
import io.github.git13166956007.dsh.model.ModelHealth;
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
    private final SubAgentRunner subAgentRunner;
    private final SubAgentSessionManager subAgentSessionManager;
    private final AdaptivePlanService adaptivePlanService;
    private final MemoryManager memoryManager;
    private final RunManager runManager;
    private final ChatModel chatModel;
    private final Path pluginDirectory;
    private final boolean memoryAutoExtractEnabled;
    private final int memoryAutoExtractMaxRecords;

    public DshController(DshRuntime runtime, AgentLoop agentLoop, ContextManager contextManager,
                         ToolRegistry toolRegistry, McpServerRegistry mcpServerRegistry,
                         McpClientManager mcpClientManager, SkillRegistry skillRegistry,
                         ModelRegistry modelRegistry, AgentProfileRegistry agentProfileRegistry,
                         PlanRegistry planRegistry, PlanExecutor planExecutor,
                         SubAgentProfileRegistry subAgentProfileRegistry, SubAgentRunner subAgentRunner,
                         SubAgentSessionManager subAgentSessionManager, AdaptivePlanService adaptivePlanService,
                         MemoryManager memoryManager, RunManager runManager, ChatModel chatModel,
                         org.springframework.core.env.Environment environment) {
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
        this.subAgentRunner = subAgentRunner;
        this.subAgentSessionManager = subAgentSessionManager;
        this.adaptivePlanService = adaptivePlanService;
        this.memoryManager = memoryManager;
        this.runManager = runManager;
        this.chatModel = chatModel;
        this.pluginDirectory = Path.of(environment.getProperty("dsh.plugins.directory", "plugins"));
        this.memoryAutoExtractEnabled = Boolean.parseBoolean(environment.getProperty("dsh.memory.auto-extract.enabled", "false"));
        this.memoryAutoExtractMaxRecords = Integer.parseInt(environment.getProperty("dsh.memory.auto-extract.max-records", "3"));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", "dsh-java");
        result.put("runtimeStarted", runtime.isStarted());
        result.put("pluginCount", runtime.pluginCount());
        result.put("plugins", runtime.pluginIds());
        return result;
    }

    @GetMapping("/plugins")
    public java.util.List<String> plugins() {
        return runtime.pluginIds();
    }

    @GetMapping("/plugins/details")
    public java.util.List<DshRuntime.PluginInfo> pluginDetails() {
        return runtime.pluginInfo();
    }

    @PostMapping("/plugins/load")
    public java.util.List<String> loadPlugins() {
        try {
            runtime.loadPlugins(pluginDirectory);
            return runtime.pluginIds();
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage(), exception);
        }
    }

    @PostMapping("/plugins/reload")
    public java.util.List<String> reloadPlugins() {
        try {
            runtime.reloadPlugins(pluginDirectory);
            return runtime.pluginIds();
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage(), exception);
        }
    }

    @PostMapping("/plugins/unload")
    public java.util.List<String> unloadPlugins() {
        return runtime.unloadPlugins();
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
                    request.command(), request.arguments(), request.credentialRef(), request.headers(), request.environment(),
                    request.approvalRequired());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PatchMapping("/mcp/servers/{id}")
    public McpServerInfo updateMcpServer(@PathVariable String id, @RequestBody McpServerRequest request) {
        try {
            McpServerInfo current = mcpServerRegistry.find(id);
            if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown MCP server: " + id);
            boolean wasConnected = "CONNECTED".equals(current.status());
            if (wasConnected) mcpClientManager.disconnect(id);
            McpServerInfo updated = mcpServerRegistry.update(id, request.name(), request.transport(), request.endpoint(),
                    request.command(), request.arguments(), request.enabled(), request.credentialRef(),
                    request.headers(), request.environment(), request.approvalRequired());
            return updated.enabled() && wasConnected ? mcpClientManager.connect(id) : updated;
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

    @GetMapping("/mcp/servers/{id}/resources")
    public java.util.List<McpResourceInfo> mcpResources(@PathVariable String id) {
        return mcpClientManager.resources(id);
    }

    @GetMapping("/mcp/servers/{id}/resources/read")
    public java.util.List<McpResourceContent> readMcpResource(@PathVariable String id, @RequestParam String uri) {
        return mcpClientManager.readResource(id, uri);
    }

    @PostMapping("/mcp/servers/{id}/resources/subscribe")
    public McpResourceSubscription subscribeMcpResource(@PathVariable String id,
                                                        @RequestBody ResourceSubscriptionRequest request) {
        if (request == null || request.uri() == null || request.uri().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "uri must not be blank");
        }
        return mcpClientManager.subscribeResource(id, request.uri());
    }

    @DeleteMapping("/mcp/servers/{id}/resources/subscribe")
    public McpResourceSubscription unsubscribeMcpResource(@PathVariable String id, @RequestParam String uri) {
        return mcpClientManager.unsubscribeResource(id, uri);
    }

    @GetMapping("/mcp/servers/{id}/resources/subscriptions")
    public java.util.List<String> mcpResourceSubscriptions(@PathVariable String id) {
        return mcpClientManager.subscriptions(id);
    }

    @GetMapping("/mcp/servers/{id}/resources/updates")
    public java.util.List<McpResourceUpdate> mcpResourceUpdates(@PathVariable String id) {
        return mcpClientManager.resourceUpdates(id);
    }

    @GetMapping("/mcp/servers/{id}/prompts")
    public java.util.List<McpPromptInfo> mcpPrompts(@PathVariable String id) {
        return mcpClientManager.prompts(id);
    }

    @PostMapping("/mcp/servers/{id}/prompts/{name}")
    public McpPromptResult getMcpPrompt(@PathVariable String id, @PathVariable String name,
                                        @RequestBody(required = false) java.util.Map<String, Object> arguments) {
        return mcpClientManager.getPrompt(id, name, arguments);
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

    @GetMapping("/mcp/servers/{id}/health")
    public McpHealth mcpHealth(@PathVariable String id) {
        try {
            return mcpClientManager.health(id);
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

    @PostMapping("/skills")
    public SkillInfo installSkill(@RequestBody SkillCreateRequest request) {
        if (request == null || request.id() == null || request.content() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id and content are required");
        }
        try {
            return skillRegistry.install(request.id(), request.name(), request.version(), request.description(),
                    request.content(), request.enabled());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (java.io.IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to write skill package", exception);
        }
    }

    @DeleteMapping("/skills/{id}")
    public void removeSkill(@PathVariable String id) {
        try {
            if (!skillRegistry.remove(id)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown skill: " + id);
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (java.io.IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to remove skill package", exception);
        }
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

    @GetMapping("/models/{id}")
    public ModelProfile model(@PathVariable String id) {
        ModelProfile profile = modelRegistry.find(id);
        if (profile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown model: " + id);
        return profile;
    }

    @GetMapping("/models/providers")
    public java.util.List<String> modelProviders() {
        return modelRegistry.providerIds();
    }

    @PostMapping("/models")
    public ModelProfile createModel(@RequestBody ModelRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model profile must not be null");
        }
        try {
            return modelRegistry.create(request.name(), request.provider(), request.baseUrl(), request.model(),
                    request.apiKey(), request.proxyHost(), request.proxyPort(), request.enabled(), request.active(),
                    request.supportsTools(), request.supportsStreaming(), request.supportsVision(), request.contextWindow(),
                    request.temperature(), request.topP(), request.maxTokens(), request.frequencyPenalty(),
                    request.presencePenalty(), request.timeoutSeconds(), request.requestOptionsJson(), request.fallbackModelId(),
                    request.failoverPolicy(), request.inputPricePerMillionTokens(), request.outputPricePerMillionTokens());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PatchMapping("/models/{id}")
    public ModelProfile updateModel(@PathVariable String id, @RequestBody JsonNode request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model patch must not be null");
        }
        try {
            return modelRegistry.update(id, request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    exception.getMessage() != null && exception.getMessage().startsWith("unknown model:")
                            ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST,
                    exception.getMessage(), exception);
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
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), exception);
        }
    }

    @PostMapping("/models/{id}/test")
    public ModelTestResponse testModel(@PathVariable String id, @RequestBody(required = false) ModelTestRequest request) {
        try {
            ModelProfile profile = modelRegistry.find(id);
            if (profile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown model: " + id);
            String prompt = request == null || request.prompt() == null || request.prompt().isBlank()
                    ? "Reply with OK." : request.prompt().trim();
            io.github.git13166956007.dsh.agent.ModelResponse response = chatModel.complete(
                    java.util.List.of(ChatMessage.system("You are a connectivity test."), ChatMessage.user(prompt)),
                    java.util.List.of(), request == null ? null : request.apiKey(), id);
            return new ModelTestResponse(id, true, "Model responded successfully", response.content());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            return new ModelTestResponse(id, false, exception.getMessage(), null);
        }
    }

    @GetMapping("/models/{id}/health")
    public ModelHealth modelHealth(@PathVariable String id) {
        try {
            return modelRegistry.health(id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
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
                    request.systemPrompt(), request.maxTurns(), request.enabled(), request.active(),
                    request.maxToolCalls(), request.timeoutSeconds(), request.maxDepth());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PatchMapping("/agents/{id}")
    public AgentProfile updateAgent(@PathVariable String id, @RequestBody AgentProfileRequest request) {
        try {
            return agentProfileRegistry.update(id, request.name(), request.mode() == null ? null : AgentMode.parse(request.mode()),
                    request.modelId(), request.systemPrompt(), request.maxTurns(), request.enabled(), request.active(),
                    request.maxToolCalls(), request.timeoutSeconds(), request.maxDepth());
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

    @GetMapping("/sub-agents/candidates")
    public java.util.List<AdaptivePlanService.SubAgentCandidate> subAgentCandidates(
            @RequestParam String task) {
        try {
            return adaptivePlanService.rankSubAgents(task);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/sub-agents")
    public SubAgentProfile createSubAgent(@RequestBody SubAgentProfileRequest request) {
        try {
            return subAgentProfileRegistry.create(request.name(), AgentMode.parse(request.mode()), request.modelId(),
                    request.systemPrompt(), request.maxTurns(), request.allowedToolNames(), request.skillIds(), request.enabled(),
                    request.maxToolCalls(), request.timeoutSeconds(), request.maxDepth(), request.priority(),
                    request.costWeight(), request.maxConcurrentRuns(), request.capabilityTags());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PatchMapping("/sub-agents/{id}")
    public SubAgentProfile updateSubAgent(@PathVariable String id, @RequestBody SubAgentProfileRequest request) {
        try {
            return subAgentProfileRegistry.update(id, request.name(), request.mode() == null ? null : AgentMode.parse(request.mode()),
                    request.modelId(), request.systemPrompt(), request.maxTurns(), request.allowedToolNames(),
                    request.skillIds(), request.enabled(), request.maxToolCalls(), request.timeoutSeconds(), request.maxDepth(),
                    request.priority(), request.costWeight(), request.maxConcurrentRuns(), request.capabilityTags());
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

    @PostMapping("/sub-agents/{id}/runs")
    public Run startSubAgentRun(@PathVariable String id, @RequestBody SubAgentRunRequest request) {
        if (request == null || request.prompt() == null || request.prompt().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt must not be blank");
        }
        try {
            AgentRunHandle handle = subAgentRunner.startForExecution(request.prompt(), request.apiKey(), id);
            Run run = runManager.find(handle.runId());
            if (run == null) throw new IllegalStateException("sub-agent run was not persisted");
            return run;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), exception);
        }
    }

    @GetMapping("/sub-agents/sessions")
    public java.util.List<SubAgentSession> subAgentSessions() throws Exception {
        return subAgentSessionManager.list();
    }

    @GetMapping("/sub-agents/sessions/{id}")
    public SubAgentSession subAgentSession(@PathVariable String id) throws Exception {
        SubAgentSession session = subAgentSessionManager.find(id);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown sub-agent session: " + id);
        return session;
    }

    @PostMapping("/sub-agents/{id}/sessions")
    public SubAgentSession createSubAgentSession(@PathVariable String id) {
        try {
            return subAgentSessionManager.create(id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), exception);
        }
    }

    @PostMapping("/sub-agents/sessions/{id}/messages")
    public Run sendSubAgentSessionMessage(@PathVariable String id, @RequestBody SubAgentRunRequest request) {
        if (request == null || request.prompt() == null || request.prompt().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt must not be blank");
        }
        try {
            AgentRunHandle handle = subAgentSessionManager.send(id, request.prompt(), request.apiKey());
            Run run = runManager.find(handle.runId());
            if (run == null) throw new IllegalStateException("sub-agent session run was not persisted");
            return run;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), exception);
        }
    }

    @PostMapping("/sub-agents/sessions/{id}/close")
    public SubAgentSession closeSubAgentSession(@PathVariable String id) {
        try {
            return subAgentSessionManager.close(id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/sub-agents/sessions/{id}")
    public void deleteSubAgentSession(@PathVariable String id) throws Exception {
        if (!subAgentSessionManager.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown sub-agent session: " + id);
        }
    }

    @GetMapping("/memories")
    public java.util.List<MemoryRecord> memories(@RequestParam String namespace,
                                                 @RequestParam String subjectKey,
                                                 @RequestParam(defaultValue = "50") int limit) throws Exception {
        return memoryManager.list(namespace, subjectKey, limit);
    }

    @GetMapping("/conversations/{id}/context")
    public ContextResponse conversationContext(@PathVariable String id,
                                               @RequestParam(required = false) String modelId) throws Exception {
        ContextWindow window = contextManager.window(id, modelContextWindow(modelId, null), modelTokenizer(modelId, null));
        return new ContextResponse(id, window.messages().size(), window.estimatedTokens(), window.maxTokens(),
                window.truncated());
    }

    @PostMapping("/conversations/{id}/compact")
    public ContextCompactResponse compactConversation(@PathVariable String id,
                                                      @RequestBody(required = false) ContextCompactRequest request)
            throws Exception {
        String apiKey = request == null ? null : request.apiKey();
        String modelId = request == null ? null : request.modelId();
        try {
            boolean compacted = contextManager.compact(id, chatModel, apiKey, modelId,
                    modelContextWindow(modelId, null), modelTokenizer(modelId, null));
            ContextWindow window = contextManager.window(id, modelContextWindow(modelId, null), modelTokenizer(modelId, null));
            return new ContextCompactResponse(id, compacted, window.messages().size(), window.estimatedTokens(),
                    window.maxTokens(), window.truncated());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
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

    @PatchMapping("/memories/{id}")
    public MemoryRecord updateMemory(@PathVariable long id, @RequestBody MemoryUpdateRequest request) throws Exception {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "memory update must not be null");
        try {
            return memoryManager.update(id, request.memoryType(), request.content(), request.metadataJson(), request.importance());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(exception.getMessage() != null && exception.getMessage().startsWith("unknown memory:")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
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

    @GetMapping(value = "/runs/{id}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runEventsStream(@PathVariable String id) throws Exception {
        Run run = runManager.find(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown run: " + id);

        SseEmitter emitter = new SseEmitter(180_000L);
        AutoCloseable subscription = runManager.subscribe(id, event -> send(emitter, "run_event", event));
        Runnable cleanup = () -> {
            try {
                subscription.close();
            } catch (Exception ignored) {
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        for (RunEvent event : runManager.events(id)) send(emitter, "run_event", event);
        if (run.status().terminal()) emitter.complete();
        return emitter;
    }

    @GetMapping("/runs/{id}/tree")
    public java.util.List<Run> runTree(@PathVariable String id) throws Exception {
        if (runManager.find(id) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown run: " + id);
        java.util.List<Run> all = runManager.list();
        java.util.Map<String, Run> byId = all.stream().collect(java.util.stream.Collectors.toMap(Run::id, run -> run));
        return all.stream().filter(run -> isDescendant(run, id, byId)).toList();
    }

    @PostMapping("/runs/{id}/cancel")
    public Run cancelRun(@PathVariable String id) throws Exception {
        Run run = runManager.find(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown run: " + id);
        if (run.kind() == io.github.git13166956007.dsh.run.RunKind.PLAN && run.planId() != null) {
            planExecutor.cancel(run.planId());
        } else {
            agentLoop.cancel(id);
            agentLoop.cancelPendingApproval(id);
            subAgentRunner.releaseCompletedReservations();
            if (!run.status().terminal() && runManager.find(id) != null
                    && !runManager.find(id).status().terminal()) runManager.cancel(id);
        }
        return runManager.find(id);
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
            AgentRunResult result = run.planId() == null
                    ? agentLoop.resumeApproval(id, request.approved(), request.apiKey())
                    : planExecutor.resumeApproval(id, request.approved(), request.apiKey());
            if (result.pendingApproval() == null && run.conversationId() != null) {
                contextManager.append(run.conversationId(), ChatMessage.assistant(result.answer(), java.util.List.of()));
            }
            subAgentSessionManager.onApprovalResult(result);
            subAgentRunner.releaseCompletedReservations();
            return new ChatResponse(run.conversationId(), result.answer(), result.trace(), result.turns(),
                    result.runId(), result.pendingApproval());
        } catch (IllegalArgumentException exception) {
            subAgentSessionManager.onApprovalFailure(id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            subAgentSessionManager.onApprovalFailure(id);
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (Exception exception) {
            subAgentSessionManager.onApprovalFailure(id);
            throw exception;
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
            compactConversationIfNeeded(conversationId, request);
            java.util.List<ChatMessage> history = contextManager.history(conversationId,
                    modelContextWindow(request.modelId(), request.agentId()),
                    modelTokenizer(request.modelId(), request.agentId()));
            contextManager.append(conversationId, ChatMessage.user(request.message()));
            AgentRunResult result = agentLoop.runDetailed(request.message(), request.apiKey(), history, request.modelId(),
                    request.agentId(), mode, "conversation", conversationId,
                    io.github.git13166956007.dsh.agent.AgentRunContext.chat(conversationId, request.agentId()));
            if (result.pendingApproval() == null) {
                contextManager.append(conversationId, ChatMessage.assistant(result.answer(), java.util.List.of()));
                extractMemories(conversationId, request, result.answer());
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
            compactConversationIfNeeded(conversationId, request);
            history = contextManager.history(conversationId,
                    modelContextWindow(request.modelId(), request.agentId()),
                    modelTokenizer(request.modelId(), request.agentId()));
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
                    extractMemories(finalConversationId, request, result.answer());
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

    private int modelContextWindow(String modelId, String agentId) {
        String selectedModelId = modelId;
        if ((selectedModelId == null || selectedModelId.isBlank()) && agentId != null && !agentId.isBlank()) {
            AgentProfile profile = agentProfileRegistry.find(agentId);
            if (profile != null) selectedModelId = profile.modelId();
        }
        return modelRegistry.resolve(selectedModelId).contextWindow();
    }

    private io.github.git13166956007.dsh.model.ModelTokenizer modelTokenizer(String modelId, String agentId) {
        String selectedModelId = modelId;
        if ((selectedModelId == null || selectedModelId.isBlank()) && agentId != null && !agentId.isBlank()) {
            AgentProfile profile = agentProfileRegistry.find(agentId);
            if (profile != null) selectedModelId = profile.modelId();
        }
        return modelRegistry.tokenizer(selectedModelId);
    }

    private void compactConversationIfNeeded(String conversationId, ChatRequest request) {
        try {
            contextManager.compact(conversationId, chatModel, request.apiKey(), request.modelId(),
                    modelContextWindow(request.modelId(), request.agentId()),
                    modelTokenizer(request.modelId(), request.agentId()));
        } catch (Exception ignored) {
            // Context compaction is best effort; truncation remains the fallback when the model is unavailable.
        }
    }

    private void extractMemories(String conversationId, ChatRequest request, String answer) {
        if (!memoryAutoExtractEnabled) return;
        try {
            memoryManager.extractAndSave(chatModel, request.apiKey(), request.modelId(),
                    "conversation", conversationId, request.message(), answer, memoryAutoExtractMaxRecords);
        } catch (Exception ignored) {
            // Memory extraction is best effort and must not change the chat result.
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

    public record SkillCreateRequest(String id, String name, String version, String description,
                                     String content, Boolean enabled) {
    }

    public record ModelRequest(String name, String provider, String baseUrl, String model, String apiKey,
                               String proxyHost, Integer proxyPort, Boolean enabled, Boolean active,
                               Boolean supportsTools, Boolean supportsStreaming, Boolean supportsVision,
                               Integer contextWindow, Double temperature, Double topP, Integer maxTokens,
                               Double frequencyPenalty, Double presencePenalty, Integer timeoutSeconds,
                               String requestOptionsJson, String fallbackModelId, String failoverPolicy,
                               Double inputPricePerMillionTokens, Double outputPricePerMillionTokens) {
    }

    public record ModelTestRequest(String apiKey, String prompt) {
    }

    public record ModelTestResponse(String modelId, boolean ok, String message, String content) {
    }

    public record ResourceSubscriptionRequest(String uri) {
    }

    public record AgentProfileRequest(String name, String mode, String modelId, String systemPrompt,
                                      Integer maxTurns, Boolean enabled, Boolean active, Integer maxToolCalls,
                                      Integer timeoutSeconds, Integer maxDepth) {
    }

    public record SubAgentProfileRequest(String name, String mode, String modelId, String systemPrompt,
                                         Integer maxTurns, java.util.List<String> allowedToolNames,
                                         java.util.List<String> skillIds, Boolean enabled, Integer maxToolCalls,
                                         Integer timeoutSeconds, Integer maxDepth, Integer priority, Double costWeight,
                                         Integer maxConcurrentRuns, java.util.List<String> capabilityTags) {
    }

    public record SubAgentRunRequest(String prompt, String apiKey) {
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

    public record MemoryUpdateRequest(String memoryType, String content, String metadataJson, Double importance) {
    }

    public record ToolCreateRequest(String name, String description, tools.jackson.databind.JsonNode parameters,
                                    String result, Boolean approvalRequired) {
    }

    public record McpServerRequest(String name, String transport, String endpoint, String command,
                                   java.util.List<String> arguments, Boolean enabled, String credentialRef,
                                   Map<String, String> headers, Map<String, String> environment,
                                   Boolean approvalRequired) {
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

    public record ApprovalRequest(Boolean approved, String apiKey) {
        public ApprovalRequest(Boolean approved) {
            this(approved, null);
        }
    }

    public record ErrorResponse(String error) {
    }

    public record ContextResponse(String conversationId, int messageCount, int estimatedTokens,
                                  int maxTokens, boolean truncated) {
    }

    public record ContextCompactRequest(String apiKey, String modelId) {
    }

    public record ContextCompactResponse(String conversationId, boolean compacted, int messageCount,
                                         int estimatedTokens, int maxTokens, boolean truncated) {
    }
}
