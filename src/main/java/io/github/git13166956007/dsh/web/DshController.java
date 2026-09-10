package io.github.git13166956007.dsh.web;

import java.util.LinkedHashMap;
import java.util.Map;
import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentStreamListener;
import io.github.git13166956007.dsh.agent.AgentRunResult;
import io.github.git13166956007.dsh.agent.ToolCall;
import io.github.git13166956007.dsh.core.DshRuntime;
import io.github.git13166956007.dsh.provider.deepseek.ModelConfigurationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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

    public DshController(DshRuntime runtime, AgentLoop agentLoop) {
        this.runtime = runtime;
        this.agentLoop = agentLoop;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", "dsh-java");
        result.put("runtimeStarted", runtime.isStarted());
        result.put("pluginCount", runtime.pluginCount());
        return result;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) throws Exception {
        if (request == null || request.message() == null || request.message().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be blank");
        }
        AgentRunResult result = agentLoop.runDetailed(request.message(), request.apiKey());
        return new ChatResponse(result.answer(), result.trace(), result.turns());
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        if (request == null || request.message() == null || request.message().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be blank");
        }

        SseEmitter emitter = new SseEmitter(180_000L);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                AgentRunResult result = agentLoop.runStreaming(request.message(), request.apiKey(), new AgentStreamListener() {
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
                send(emitter, "done", result);
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

    public record ChatRequest(String message, String apiKey) {
    }

    public record ChatResponse(String message, java.util.List<io.github.git13166956007.dsh.agent.AgentTraceEvent> trace,
                                int turns) {
    }

    public record ErrorResponse(String error) {
    }
}
