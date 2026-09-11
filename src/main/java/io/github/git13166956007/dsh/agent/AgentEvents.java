package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.tool.ToolDefinition;
import java.util.List;
import io.github.git13166956007.dsh.event.EventKey;
import tools.jackson.databind.JsonNode;

/** Typed extension points around one agent run. */
public final class AgentEvents {
    public static final EventKey<RunEvent> RUN = new EventKey<RunEvent>("agent.run", RunEvent.class);
    public static final EventKey<ModelRequest> MODEL_REQUEST =
            new EventKey<ModelRequest>("agent.model.request", ModelRequest.class);
    public static final EventKey<ModelResponseEvent> MODEL_RESPONSE =
            new EventKey<ModelResponseEvent>("agent.model.response", ModelResponseEvent.class);
    public static final EventKey<ToolCallEvent> TOOL_CALL =
            new EventKey<ToolCallEvent>("agent.tool.call", ToolCallEvent.class);
    public static final EventKey<ToolResultEvent> TOOL_RESULT =
            new EventKey<ToolResultEvent>("agent.tool.result", ToolResultEvent.class);
    public static final EventKey<StreamDelta> STREAM_DELTA =
            new EventKey<StreamDelta>("agent.model.delta", StreamDelta.class);

    private AgentEvents() { }

    public enum Phase { STARTED, MODEL_REQUESTED, MODEL_RESPONDED, TOOL_REQUESTED, TOOL_COMPLETED, COMPLETED, FAILED }

    public record RunEvent(String runId, String agentId, Phase phase, String detail) { }

    public record ModelRequest(String runId, List<ChatMessage> messages, List<ToolDefinition> tools,
                                String apiKey, String modelId, boolean streaming) {
        public ModelRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    public record ModelResponseEvent(String runId, ModelResponse response) { }

    public record ToolCallEvent(String runId, ToolCall call) { }

    public record ToolResultEvent(String runId, ToolCall call, String result) { }

    public record StreamDelta(String runId, String kind, String delta) { }
}
