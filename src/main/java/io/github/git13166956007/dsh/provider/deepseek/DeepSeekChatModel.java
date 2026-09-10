package io.github.git13166956007.dsh.provider.deepseek;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.agent.ModelStreamListener;
import io.github.git13166956007.dsh.model.ModelCatalogEntry;
import io.github.git13166956007.dsh.provider.openai.OpenAiCompatibleChatModel;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

public final class DeepSeekChatModel implements ChatModel {
    private final OpenAiCompatibleChatModel delegate;

    public DeepSeekChatModel(ObjectMapper objectMapper, String baseUrl, String apiKey, String model) {
        this(objectMapper, baseUrl, apiKey, model, "", 0);
    }

    public DeepSeekChatModel(ObjectMapper objectMapper, String baseUrl, String apiKey,
                             String model, String proxyHost, int proxyPort) {
        this(objectMapper, baseUrl, apiKey, model, proxyHost, proxyPort,
                null, null, null, null, null, 120);
    }

    public DeepSeekChatModel(ObjectMapper objectMapper, String baseUrl, String apiKey,
                             String model, String proxyHost, int proxyPort,
                             Double temperature, Double topP, Integer maxTokens,
                             Double frequencyPenalty, Double presencePenalty, int timeoutSeconds) {
        this(objectMapper, baseUrl, apiKey, model, proxyHost, proxyPort, temperature, topP, maxTokens,
                frequencyPenalty, presencePenalty, timeoutSeconds, null);
    }

    public DeepSeekChatModel(ObjectMapper objectMapper, String baseUrl, String apiKey,
                             String model, String proxyHost, int proxyPort,
                             Double temperature, Double topP, Integer maxTokens,
                             Double frequencyPenalty, Double presencePenalty, int timeoutSeconds,
                             String requestOptionsJson) {
        this.delegate = new OpenAiCompatibleChatModel(objectMapper, "DeepSeek", baseUrl, apiKey, model,
                proxyHost, proxyPort, temperature, topP, maxTokens, frequencyPenalty, presencePenalty, timeoutSeconds,
                requestOptionsJson);
    }

    @Override
    public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws Exception {
        return delegate.complete(messages, tools);
    }

    @Override
    public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 String requestApiKey) throws Exception {
        return delegate.complete(messages, tools, requestApiKey);
    }

    @Override
    public ModelResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                String requestApiKey, ModelStreamListener listener) throws Exception {
        return delegate.stream(messages, tools, requestApiKey, listener);
    }

    public List<ModelCatalogEntry> listModels(String requestApiKey) throws Exception {
        return delegate.listModels(requestApiKey);
    }
}
