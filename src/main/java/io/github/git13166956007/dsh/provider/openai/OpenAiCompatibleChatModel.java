package io.github.git13166956007.dsh.provider.openai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.agent.ModelStreamListener;
import io.github.git13166956007.dsh.agent.ToolCall;
import io.github.git13166956007.dsh.model.ModelCatalogEntry;
import io.github.git13166956007.dsh.provider.deepseek.ModelConfigurationException;
import io.github.git13166956007.dsh.provider.deepseek.ModelQuotaException;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenAiCompatibleChatModel implements ChatModel {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String provider;
    private final String apiKey;
    private final String model;
    private final URI baseEndpoint;
    private final URI endpoint;
    private final Double temperature;
    private final Double topP;
    private final Integer maxTokens;
    private final Double frequencyPenalty;
    private final Double presencePenalty;
    private final int timeoutSeconds;
    private final String requestOptionsJson;

    public OpenAiCompatibleChatModel(ObjectMapper objectMapper, String provider, String baseUrl,
                                     String apiKey, String model, String proxyHost, int proxyPort) {
        this(objectMapper, provider, baseUrl, apiKey, model, proxyHost, proxyPort,
                null, null, null, null, null, 120);
    }

    public OpenAiCompatibleChatModel(ObjectMapper objectMapper, String provider, String baseUrl,
                                     String apiKey, String model, String proxyHost, int proxyPort,
                                     Double temperature, Double topP, Integer maxTokens,
                                     Double frequencyPenalty, Double presencePenalty, int timeoutSeconds) {
        this(objectMapper, provider, baseUrl, apiKey, model, proxyHost, proxyPort, temperature, topP, maxTokens,
                frequencyPenalty, presencePenalty, timeoutSeconds, null);
    }

    public OpenAiCompatibleChatModel(ObjectMapper objectMapper, String provider, String baseUrl,
                                     String apiKey, String model, String proxyHost, int proxyPort,
                                     Double temperature, Double topP, Integer maxTokens,
                                     Double frequencyPenalty, Double presencePenalty, int timeoutSeconds,
                                     String requestOptionsJson) {
        this.objectMapper = objectMapper;
        this.provider = provider == null || provider.isBlank() ? "openai_compatible" : provider;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
        this.maxTokens = maxTokens;
        this.frequencyPenalty = frequencyPenalty;
        this.presencePenalty = presencePenalty;
        this.timeoutSeconds = timeoutSeconds;
        this.requestOptionsJson = requestOptionsJson;
        this.baseEndpoint = URI.create(trimTrailingSlash(baseUrl));
        this.endpoint = URI.create(this.baseEndpoint + "/chat/completions");
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
        HttpResponse<String> response = httpClient.send(buildRequest(request, effectiveApiKey, false),
                HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response.statusCode(), response.body());

        JsonNode root = objectMapper.readTree(response.body());
        return parseCompletion(root);
    }

    @Override
    public ModelResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                String requestApiKey, ModelStreamListener listener) throws Exception {
        String effectiveApiKey = resolveApiKey(requestApiKey);
        HttpResponse<InputStream> response = httpClient.send(
                buildRequest(requestJson(messages, tools, true), effectiveApiKey, true),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            ensureSuccess(response.statusCode(), body);
        }

        StringBuilder content = new StringBuilder();
        StringBuilder nonSseBody = new StringBuilder();
        Map<Integer, PartialToolCall> partialCalls = new LinkedHashMap<Integer, PartialToolCall>();
        String finishReason = null;
        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;
        StringBuilder reasoning = new StringBuilder();
        boolean sawSsePayload = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    if (!line.isBlank()) nonSseBody.append(line).append('\n');
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                sawSsePayload = true;
                if (data.isEmpty()) continue;

                JsonNode chunk = objectMapper.readTree(data);
                JsonNode usage = chunk.path("usage");
                if (usage.isObject()) {
                    promptTokens = integerValue(usage, "prompt_tokens", promptTokens);
                    completionTokens = integerValue(usage, "completion_tokens", completionTokens);
                    totalTokens = integerValue(usage, "total_tokens", totalTokens);
                }
                JsonNode choice = chunk.path("choices").path(0);
                JsonNode delta = choice.path("delta");
                String text = delta.path("content").asText(null);
                if (text != null && !text.isEmpty()) {
                    content.append(text);
                    listener.onText(text);
                }
                String reasoningDelta = textValue(delta, "reasoning_content");
                if (reasoningDelta == null) reasoningDelta = textValue(delta, "reasoning");
                if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                    reasoning.append(reasoningDelta);
                    listener.onReasoning(reasoningDelta);
                }
                if (!choice.path("finish_reason").isMissingNode()
                        && !choice.path("finish_reason").isNull()) {
                    finishReason = choice.path("finish_reason").asText(null);
                }
                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode toolCall : toolCalls) {
                        int index = toolCall.path("index").asInt(partialCalls.size());
                        PartialToolCall partial = partialCalls.computeIfAbsent(index, ignored -> new PartialToolCall());
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

        if (!sawSsePayload) {
            String fallbackBody = nonSseBody.toString().trim();
            if (!fallbackBody.isEmpty()) {
                try {
                    JsonNode root = objectMapper.readTree(fallbackBody);
                    if (root != null && root.path("choices").isArray() && root.path("choices").size() > 0) {
                        return parseCompletion(root);
                    }
                } catch (Exception ignored) {
                    // Report the original non-SSE body below with a bounded diagnostic.
                }
            }
            throw new IllegalStateException(provider + " API returned 200 without an SSE payload: "
                    + diagnostic(fallbackBody));
        }

        List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        for (PartialToolCall partial : partialCalls.values()) {
            String arguments = partial.arguments.length() == 0 ? "{}" : partial.arguments.toString();
            toolCalls.add(new ToolCall(partial.id, partial.name, objectMapper.readTree(arguments)));
        }
        return new ModelResponse(content.length() == 0 ? null : content.toString(), toolCalls, finishReason,
                promptTokens, completionTokens, totalTokens, reasoning.length() == 0 ? null : reasoning.toString());
    }

    public List<ModelCatalogEntry> listModels(String requestApiKey) throws Exception {
        String effectiveApiKey = resolveApiKey(requestApiKey);
        HttpRequest request = HttpRequest.newBuilder(modelsEndpoint())
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + effectiveApiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response.statusCode(), response.body());
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode data = root != null && root.path("data").isArray() ? root.path("data") : root;
        if (data == null || !data.isArray()) return List.of();
        List<ModelCatalogEntry> result = new ArrayList<ModelCatalogEntry>();
        for (JsonNode item : data) {
            String id = item.path("id").asText(null);
            if (id == null || id.isBlank()) continue;
            Long created = item.path("created").isIntegralNumber() ? item.path("created").asLong() : null;
            String ownedBy = item.path("owned_by").asText(null);
            result.add(new ModelCatalogEntry(id, item.path("object").asText("model"), created, ownedBy));
        }
        return result;
    }

    private ModelResponse parseCompletion(JsonNode root) throws Exception {
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").isNull() ? null : message.path("content").asText(null);
        String reasoning = textValue(message, "reasoning_content");
        if (reasoning == null) reasoning = textValue(message, "reasoning");
        List<ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        JsonNode usage = root.path("usage");
        return new ModelResponse(content, toolCalls, choice.path("finish_reason").asText(null),
                integerValue(usage, "prompt_tokens", null), integerValue(usage, "completion_tokens", null),
                integerValue(usage, "total_tokens", null), reasoning);
    }

    private static Integer integerValue(JsonNode object, String field, Integer fallback) {
        if (object == null || !object.isObject() || !object.has(field) || object.path(field).isNull()) return fallback;
        return object.path(field).isIntegralNumber() ? object.path(field).asInt() : fallback;
    }

    private static String textValue(JsonNode object, String field) {
        if (object == null || !object.isObject() || !object.has(field) || object.path(field).isNull()) return null;
        String value = object.path(field).asText(null);
        return value == null || value.isEmpty() ? null : value;
    }

    private static String diagnostic(String body) {
        if (body == null || body.isBlank()) return "empty response";
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000) + "...";
    }

    private String resolveApiKey(String requestApiKey) {
        String effectiveApiKey = requestApiKey == null || requestApiKey.trim().isEmpty()
                ? apiKey : requestApiKey.trim();
        if (effectiveApiKey == null || effectiveApiKey.trim().isEmpty()) {
            throw new ModelConfigurationException(provider + " API key is not configured");
        }
        return effectiveApiKey;
    }

    private ObjectNode requestJson(List<ChatMessage> messages, List<ToolDefinition> tools, boolean stream) {
        ObjectNode request = objectMapper.createObjectNode();
        applyRequestOptions(request);
        request.put("model", model);
        request.put("stream", stream);
        if (temperature != null) request.put("temperature", temperature);
        if (topP != null) request.put("top_p", topP);
        if (maxTokens != null) request.put("max_tokens", maxTokens);
        if (frequencyPenalty != null) request.put("frequency_penalty", frequencyPenalty);
        if (presencePenalty != null) request.put("presence_penalty", presencePenalty);
        request.set("messages", messagesJson(messages));
        if (!tools.isEmpty()) {
            request.set("tools", toolsJson(tools));
            request.put("tool_choice", "auto");
        }
        return request;
    }

    private void applyRequestOptions(ObjectNode request) {
        if (requestOptionsJson == null || requestOptionsJson.isBlank()) return;
        try {
            JsonNode options = objectMapper.readTree(requestOptionsJson);
            if (options != null && options.isObject()) {
                options.properties().forEach(entry -> request.set(entry.getKey(), entry.getValue()));
            }
        } catch (Exception exception) {
            throw new ModelConfigurationException(provider + " requestOptionsJson is invalid");
        }
    }

    private HttpRequest buildRequest(ObjectNode request, String effectiveApiKey, boolean streaming) throws Exception {
        return HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + effectiveApiKey)
                .header("Content-Type", "application/json")
                .header("Accept", streaming ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();
    }

    private URI modelsEndpoint() {
        return URI.create(baseEndpoint + "/models");
    }

    private void ensureSuccess(int statusCode, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            if (statusCode == 402 || body.contains("Insufficient Balance")) {
                throw new ModelQuotaException(provider + " API 余额不足，请充值当前账户或更换有余额的 API Key。");
            }
            throw new IllegalStateException(provider + " API returned " + statusCode + ": " + body);
        }
    }

    private ArrayNode messagesJson(List<ChatMessage> messages) {
        ArrayNode result = objectMapper.createArrayNode();
        for (ChatMessage message : messages) {
            ObjectNode item = result.addObject();
            item.put("role", message.role().value());
            if (message.content() == null) item.putNull("content");
            else item.put("content", message.content());
            if (message.reasoningContent() != null && !message.reasoningContent().isEmpty()) {
                item.put("reasoning_content", message.reasoningContent());
            }
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
