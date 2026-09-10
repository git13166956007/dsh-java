package io.github.git13166956007.dsh.mcp;

import java.util.List;

public record McpPromptInfo(
        String serverId,
        String name,
        String title,
        String description,
        List<String> argumentNames) {
    public McpPromptInfo {
        argumentNames = List.copyOf(argumentNames == null ? List.of() : argumentNames);
    }
}
