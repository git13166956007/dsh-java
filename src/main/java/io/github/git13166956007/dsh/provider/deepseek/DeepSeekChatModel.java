package io.github.git13166956007.dsh.provider.deepseek;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.agent.ModelStreamListener;
import io.github.git13166956007.dsh.agent.ToolCall;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeepSeekChatModel implements ChatModel {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final URI endpoint;

    public DeepSeekChatModel(ObjectMapper objectMapper, String baseUrl, String apiKey, String model) {
        this(objectMapper, baseUrl, apiKey, model, "", 0);
    }

    public DeepSeekChatModel(ObjectMapper objectMapper, String baseUrl, String apiKey,
                             String model, String proxyHost, int proxyPort) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = URI.create(trimTrailingSlash(baseUrl) + "/chat/completions");
        HttpClient.Builder client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        if (proxyHost != null && !proxyHost.trim().isEmpty() && proxyPort > 0) {
            client.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        this.httpClient = client.build();
    }

    @Override
    public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws Exception {
        return complete(messages, tools, null);
    }

    @Override
    public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 String requestApiKey) throws Exception {
        String effectiveApiKey = resolveApiKey(requestApiKey);
        ObjectNode request = requestJson(messages, tools, false);
        HttpResponse<String> response = httpClient.send(buildRequest(request, effectiveApiKey),
                HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response.statusCode(), response.body());

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").isNull() ? null : message.path("content").asText(null);
        List<ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        return new ModelResponse(content, toolCalls, choice.path("finish_reason").asText(null));
    }

    @Override
    public ModelResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                String requestApiKey, ModelStreamListener listener) throws Exception {
        String effectiveApiKey = resolveApiKey(requestApiKey);
        HttpResponse<InputStream> response = httpClient.send(
                buildRequest(requestJson(messages, tools, true), effectiveApiKey),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            throw new IllegalStateException("DeepSeek API returned " + response.statusCode() + ": " + body);
        }

        StringBuilder content = new StringBuilder();
        Map<Integer, PartialToolCall> partialCalls = new LinkedHashMap<Integer, PartialToolCall>();
        String finishReason = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;

                JsonNode chunk = objectMapper.readTree(data);
                JsonNode choice = chunk.path("choices").path(0);
                JsonNode delta = choice.path("delta");
                String text = delta.path("content").asText(null);
                if (text != null && !text.isEmpty()) {
                    content.append(text);
                    listener.onText(text);
                }
                if (!choice.path("finish_reason").isMissingNode()
                        && !choice.path("finish_reason").isNull()) {
                    finishReason = choice.path("finish_reason").asText(null);
                }
                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode toolCall : toolCalls) {
                        int index = toolCall.path("index").asInt(partialCalls.size());
                        PartialToolCall partial = partialCalls.get(index);
                        if (partial == null) {
                            partial = new PartialToolCall();
                            partialCalls.put(index, partial);
                        }
                        if (toolCall.hasNonNull("id")) partial.id = toolCall.path("id").asText();
                        JsonNode function = toolCall.path("function");
                        if (function.hasNonNull("name")) partial.name = function.path("name").asText();
                        if (function.hasNonNull("arguments")) {
                            partial.arguments.append(function.path("arguments").asText());
                        }
                    }
                }
            }
        }

        List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        for (PartialToolCall partial : partialCalls.values()) {
            String arguments = partial.arguments.length() == 0 ? "{}" : partial.arguments.toString();
            toolCalls.add(new ToolCall(partial.id, partial.name, objectMapper.readTree(arguments)));
        }
        return new ModelResponse(content.length() == 0 ? null : content.toString(), toolCalls, finishReason);
    }

    private String resolveApiKey(String requestApiKey) {
        String effectiveApiKey = requestApiKey == null || requestApiKey.trim().isEmpty()
                ? apiKey : requestApiKey.trim();
        if (effectiveApiKey == null || effectiveApiKey.trim().isEmpty()) {
            throw new ModelConfigurationException("DEEPSEEK_API_KEY is not configured");
        }
        return effectiveApiKey;
    }

    private ObjectNode requestJson(List<ChatMessage> messages, List<ToolDefinition> tools, boolean stream) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        request.put("stream", stream);
        request.set("messages", messagesJson(messages));
        if (!tools.isEmpty()) {
            request.set("tools", toolsJson(tools));
            request.put("tool_choice", "auto");
        }
        return request;
    }

    private HttpRequest buildRequest(ObjectNode request, String effectiveApiKey) throws Exception {
        return HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + effectiveApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();
    }

    private static void ensureSuccess(int statusCode, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("DeepSeek API returned " + statusCode + ": " + body);
        }
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

    private static final class PartialToolCall {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
