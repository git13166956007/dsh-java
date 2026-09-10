package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.tool.ToolDefinition;
import java.util.List;

public interface ChatModel {
    ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws Exception;

    default ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools,
                                   String apiKey) throws Exception {
        return complete(messages, tools);
    }

    default ModelResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                 String apiKey, ModelStreamListener listener) throws Exception {
        return complete(messages, tools, apiKey);
    }
}
