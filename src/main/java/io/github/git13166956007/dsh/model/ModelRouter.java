package io.github.git13166956007.dsh.model;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.agent.ModelStreamListener;
import io.github.git13166956007.dsh.provider.deepseek.DeepSeekChatModel;
import io.github.git13166956007.dsh.provider.openai.OpenAiCompatibleChatModel;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;

public final class ModelRouter implements ChatModel {
    private final ModelRegistry registry;
    private final ObjectMapper objectMapper;

    public ModelRouter(ModelRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws Exception {
        return complete(messages, tools, null, null);
    }

    @Override
    public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools,
                                  String apiKey, String modelId) throws Exception {
        ModelProfileData profile = registry.resolve(modelId);
        return client(profile).complete(messages, effectiveTools(profile, tools), apiKey);
    }

    @Override
    public ModelResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                String apiKey, String modelId, ModelStreamListener listener) throws Exception {
        ModelProfileData profile = registry.resolve(modelId);
        List<ToolDefinition> effectiveTools = effectiveTools(profile, tools);
        if (!profile.supportsStreaming()) {
            ModelResponse response = client(profile).complete(messages, effectiveTools, apiKey);
            if (response.content() != null && !response.content().isEmpty()) listener.onText(response.content());
            return response;
        }
        return client(profile).stream(messages, effectiveTools, apiKey, listener);
    }

    private static List<ToolDefinition> effectiveTools(ModelProfileData profile, List<ToolDefinition> tools) {
        return profile.supportsTools() ? tools : List.of();
    }

    private ChatModel client(ModelProfileData profile) {
        String provider = profile.provider().toLowerCase(Locale.ROOT);
        if ("deepseek".equals(provider)) {
            return new DeepSeekChatModel(objectMapper, profile.baseUrl(), profile.apiKey(), profile.model(),
                    profile.proxyHost(), profile.proxyPort());
        }
        if ("openai".equals(provider) || "openai_compatible".equals(provider)) {
            return new OpenAiCompatibleChatModel(objectMapper, provider, profile.baseUrl(), profile.apiKey(),
                    profile.model(), profile.proxyHost(), profile.proxyPort());
        }
        throw new IllegalArgumentException("unsupported model provider: " + profile.provider()
                + "; use deepseek, openai, or openai_compatible");
    }
}
