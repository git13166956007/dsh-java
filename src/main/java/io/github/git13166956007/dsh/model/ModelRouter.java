package io.github.git13166956007.dsh.model;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.agent.ModelStreamListener;
import io.github.git13166956007.dsh.provider.deepseek.DeepSeekChatModel;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

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
        return client(registry.resolve(modelId)).complete(messages, tools, apiKey);
    }

    @Override
    public ModelResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                String apiKey, String modelId, ModelStreamListener listener) throws Exception {
        return client(registry.resolve(modelId)).stream(messages, tools, apiKey, listener);
    }

    private DeepSeekChatModel client(ModelProfileData profile) {
        return new DeepSeekChatModel(objectMapper, profile.baseUrl(), profile.apiKey(), profile.model(),
                profile.proxyHost(), profile.proxyPort());
    }
}
