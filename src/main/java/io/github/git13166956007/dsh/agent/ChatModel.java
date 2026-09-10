package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.tool.ToolDefinition;
import java.util.List;

public interface ChatModel {
    ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws Exception;
}
