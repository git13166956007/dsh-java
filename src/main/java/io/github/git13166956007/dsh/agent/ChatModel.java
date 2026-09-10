package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.tool.ToolDefinition;
import io.github.git13166956007.dsh.model.ModelTokenizer;
import java.util.List;

public interface ChatModel {
    ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws Exception;

    default ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools,
                                   String apiKey) throws Exception {
        return complete(messages, tools);
    }

    default ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools,
                                   String apiKey, String modelId) throws Exception {
        return complete(messages, tools, apiKey);
    }

    default ModelResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 String apiKey, ModelStreamListener listener) throws Exception {
        return complete(messages, tools, apiKey);
    }

    default ModelResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 String apiKey, String modelId, ModelStreamListener listener) throws Exception {
        return stream(messages, tools, apiKey, listener);
    }

    default ModelTokenizer tokenizer(String modelId) {
        return ModelTokenizer.approximate();
    }

    default int contextWindow(String modelId) {
        return 0;
    }
}
