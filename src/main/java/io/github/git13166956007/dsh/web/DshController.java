package io.github.git13166956007.dsh.web;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;
import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentStreamListener;
import io.github.git13166956007.dsh.agent.AgentRunResult;
import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ToolCall;
import io.github.git13166956007.dsh.context.ContextManager;
import io.github.git13166956007.dsh.mcp.McpServerInfo;
import io.github.git13166956007.dsh.mcp.McpServerRegistry;
import io.github.git13166956007.dsh.tool.ToolInfo;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.core.DshRuntime;
import io.github.git13166956007.dsh.provider.deepseek.ModelConfigurationException;
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

    public DshController(DshRuntime runtime, AgentLoop agentLoop, ContextManager contextManager,
                         ToolRegistry toolRegistry, McpServerRegistry mcpServerRegistry) {
        this.runtime = runtime;
        this.agentLoop = agentLoop;
        this.contextManager = contextManager;
        this.toolRegistry = toolRegistry;
        this.mcpServerRegistry = mcpServerRegistry;
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
                    (ObjectNode) request.parameters()), request.result());
            return toolRegistry.list().stream()
                    .filter(tool -> tool.name().equals(request.name().trim()))
                    .findFirst().orElseThrow();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PatchMapping("/tools/{name}")
    public ToolInfo updateTool(@PathVariable String name, @RequestBody ToolUpdateRequest request) {
        if (request == null || request.enabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled must be provided");
        }
        if (!toolRegistry.setEnabled(name, request.enabled())) {
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
            return mcpServerRegistry.update(id, request.name(), request.transport(), request.endpoint(),
                    request.command(), request.arguments(), request.enabled());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/mcp/servers/{id}")
    public void deleteMcpServer(@PathVariable String id) {
        if (!mcpServerRegistry.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown MCP server: " + id);
        }
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) throws Exception {
        if (request == null || request.message() == null || request.message().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be blank");
        }
        try {
            String conversationId = contextManager.open(request.conversationId(), request.message());
            java.util.List<ChatMessage> history = contextManager.history(conversationId);
            contextManager.append(conversationId, ChatMessage.user(request.message()));
            AgentRunResult result = agentLoop.runDetailed(request.message(), request.apiKey(), history);
            contextManager.append(conversationId, ChatMessage.assistant(result.answer(), java.util.List.of()));
            return new ChatResponse(conversationId, result.answer(), result.trace(), result.turns());
        } catch (Exception exception) {
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
                AgentRunResult result = agentLoop.runStreaming(request.message(), request.apiKey(), finalHistory, new AgentStreamListener() {
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
                contextManager.append(finalConversationId, ChatMessage.assistant(result.answer(), java.util.List.of()));
                send(emitter, "done", new StreamResponse(finalConversationId, result.answer(), result.trace(), result.turns()));
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

    @ExceptionHandler(ModelConfigurationException.class)
    public ResponseEntity<ErrorResponse> modelConfigurationError(ModelConfigurationException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(exception.getMessage()));
    }

    public record ChatRequest(String message, String apiKey, String conversationId) {
    }

    public record ToolUpdateRequest(Boolean enabled) {
    }

    public record ToolCreateRequest(String name, String description, tools.jackson.databind.JsonNode parameters,
                                    String result) {
    }

    public record McpServerRequest(String name, String transport, String endpoint, String command,
                                   java.util.List<String> arguments, Boolean enabled) {
    }

    public record ChatResponse(String conversationId, String message,
                               java.util.List<io.github.git13166956007.dsh.agent.AgentTraceEvent> trace,
                                int turns) {
    }

    public record StreamResponse(String conversationId, String answer,
                                 java.util.List<io.github.git13166956007.dsh.agent.AgentTraceEvent> trace,
                                 int turns) {
    }

    public record ErrorResponse(String error) {
    }
}
