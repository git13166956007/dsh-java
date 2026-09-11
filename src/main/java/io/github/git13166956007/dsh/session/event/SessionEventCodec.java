package io.github.git13166956007.dsh.session.event;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ToolCall;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class SessionEventCodec {
    private SessionEventCodec() { }

    public static ObjectNode message(ObjectMapper mapper, ChatMessage message) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("role", message.role().value());
        if (message.content() == null) payload.putNull("content"); else payload.put("content", message.content());
        if (message.reasoningContent() == null) payload.putNull("reasoningContent");
        else payload.put("reasoningContent", message.reasoningContent());
        if (message.toolCallId() == null) payload.putNull("toolCallId"); else payload.put("toolCallId", message.toolCallId());
        ArrayNode calls = payload.putArray("toolCalls");
        for (ToolCall call : message.toolCalls()) {
            ObjectNode item = calls.addObject();
            if (call.id() == null) item.putNull("id"); else item.put("id", call.id());
            if (call.name() == null) item.putNull("name"); else item.put("name", call.name());
            item.set("arguments", call.arguments() == null ? mapper.createObjectNode() : call.arguments().deepCopy());
        }
        return payload;
    }

    public static ChatMessage readMessage(JsonNode payload) {
        String role = payload.path("role").asString("user");
        String content = payload.path("content").isNull() ? null : payload.path("content").asString(null);
        String reasoning = payload.path("reasoningContent").isNull()
                ? null : payload.path("reasoningContent").asString(null);
        String toolCallId = payload.path("toolCallId").isNull()
                ? null : payload.path("toolCallId").asString(null);
        return switch (role) {
            case "system" -> ChatMessage.system(content);
            case "assistant" -> ChatMessage.assistant(content, readToolCalls(payload.path("toolCalls")), reasoning);
            case "tool" -> ChatMessage.tool(toolCallId, content);
            default -> ChatMessage.user(content);
        };
    }

    private static List<ToolCall> readToolCalls(JsonNode array) {
        List<ToolCall> calls = new ArrayList<ToolCall>();
        if (array == null || !array.isArray()) return calls;
        for (JsonNode node : array) {
            calls.add(new ToolCall(node.path("id").asString(null), node.path("name").asString(null),
                    node.path("arguments").deepCopy()));
        }
        return calls;
    }
}
