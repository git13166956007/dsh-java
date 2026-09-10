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
        String status,
        String credentialRef,
        List<String> headerNames,
        List<String> environmentNames) {
    public McpServerInfo(String id, String name, String transport, String endpoint, String command,
                         List<String> arguments, boolean enabled, String status) {
        this(id, name, transport, endpoint, command, arguments, enabled, status, null, List.of(), List.of());
    }

    public McpServerInfo {
        arguments = List.copyOf(arguments == null ? List.of() : arguments);
        credentialRef = credentialRef == null || credentialRef.isBlank() ? null : credentialRef.trim();
        headerNames = List.copyOf(headerNames == null ? List.of() : headerNames);
        environmentNames = List.copyOf(environmentNames == null ? List.of() : environmentNames);
    }
}
