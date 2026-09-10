package io.github.git13166956007.dsh.mcp;

import java.util.List;

public record McpPromptResult(
        String description,
        List<McpPromptMessage> messages) {
    public McpPromptResult {
        messages = List.copyOf(messages == null ? List.of() : messages);
    }
}
