package io.github.git13166956007.dsh.mcp;

import java.util.List;

public record McpServerInfo(
        String id,
        String name,
        String transport,
        String endpoint,
        String command,
        List<String> arguments,
        boolean enabled,
        String status) {
    public McpServerInfo {
        arguments = List.copyOf(arguments == null ? List.of() : arguments);
    }
}
