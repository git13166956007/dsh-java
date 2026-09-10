package io.github.git13166956007.dsh.provider.deepseek;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.agent.ToolCall;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class DeepSeekChatModel implements ChatModel {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final URI endpoint;

    public DeepSeekChatModel(ObjectMapper objectMapper, String baseUrl, String apiKey, String model) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = URI.create(trimTrailingSlash(baseUrl) + "/chat/completions");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ModelConfigurationException("DEEPSEEK_API_KEY is not configured");
        }

        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        request.put("stream", false);
        request.set("messages", messagesJson(messages));
        if (!tools.isEmpty()) {
            request.set("tools", toolsJson(tools));
            request.put("tool_choice", "auto");
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("DeepSeek API returned " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").isNull() ? null : message.path("content").asText(null);
        List<ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        return new ModelResponse(content, toolCalls, choice.path("finish_reason").asText(null));
    }

    private ArrayNode messagesJson(List<ChatMessage> messages) {
        ArrayNode result = objectMapper.createArrayNode();
        for (ChatMessage message : messages) {
            ObjectNode item = result.addObject();
            item.put("role", message.role().value());
            if (message.content() == null) item.putNull("content");
            else item.put("content", message.content());
            if (message.toolCallId() != null) item.put("tool_call_id", message.toolCallId());
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = item.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode toolCall = calls.addObject();
                    toolCall.put("id", call.id());
                    toolCall.put("type", "function");
                    ObjectNode function = toolCall.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments().toString());
                }
            }
        }
        return result;
    }

    private ArrayNode toolsJson(List<ToolDefinition> tools) {
        ArrayNode result = objectMapper.createArrayNode();
        for (ToolDefinition definition : tools) {
            ObjectNode tool = result.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.set("parameters", definition.parameters().deepCopy());
        }
        return result;
    }

    private List<ToolCall> parseToolCalls(JsonNode calls) throws Exception {
        List<ToolCall> result = new ArrayList<ToolCall>();
        if (!calls.isArray()) return result;
        for (JsonNode call : calls) {
            JsonNode function = call.path("function");
            String arguments = function.path("arguments").asText("{}");
            result.add(new ToolCall(call.path("id").asText(), function.path("name").asText(),
                    objectMapper.readTree(arguments)));
        }
        return result;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
