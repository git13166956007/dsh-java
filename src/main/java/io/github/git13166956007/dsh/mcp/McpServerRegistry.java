package io.github.git13166956007.dsh.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class McpServerRegistry {
    private static final List<String> SUPPORTED_TRANSPORTS = List.of("stdio", "sse", "streamable_http");
    private final Map<String, McpServerInfo> servers = new LinkedHashMap<String, McpServerInfo>();

    public synchronized McpServerInfo create(String name, String transport, String endpoint,
                                              String command, List<String> arguments) {
        String normalizedName = required(name, "name");
        String normalizedTransport = normalizeTransport(transport);
        validateTransport(normalizedTransport, endpoint, command);
        if (servers.values().stream().anyMatch(server -> server.name().equalsIgnoreCase(normalizedName))) {
            throw new IllegalArgumentException("duplicate MCP server: " + normalizedName);
        }
        McpServerInfo server = new McpServerInfo(UUID.randomUUID().toString(), normalizedName,
                normalizedTransport, blankToNull(endpoint), blankToNull(command), arguments, true, "DISCONNECTED");
        servers.put(server.id(), server);
        return server;
    }

    public synchronized List<McpServerInfo> list() {
        return new ArrayList<McpServerInfo>(servers.values());
    }

    public synchronized McpServerInfo update(String id, String name, String transport, String endpoint,
                                              String command, List<String> arguments, Boolean enabled) {
        McpServerInfo current = require(id);
        String nextName = name == null ? current.name() : required(name, "name");
        String nextTransport = transport == null ? current.transport() : normalizeTransport(transport);
        String nextEndpoint = endpoint == null ? current.endpoint() : blankToNull(endpoint);
        String nextCommand = command == null ? current.command() : blankToNull(command);
        validateTransport(nextTransport, nextEndpoint, nextCommand);
        if (servers.values().stream().anyMatch(server -> !server.id().equals(id)
                && server.name().equalsIgnoreCase(nextName))) {
            throw new IllegalArgumentException("duplicate MCP server: " + nextName);
        }
        McpServerInfo updated = new McpServerInfo(id, nextName, nextTransport, nextEndpoint, nextCommand,
                arguments == null ? current.arguments() : arguments,
                enabled == null ? current.enabled() : enabled, "DISCONNECTED");
        servers.put(id, updated);
        return updated;
    }

    public synchronized boolean delete(String id) {
        return servers.remove(id) != null;
    }

    private McpServerInfo require(String id) {
        McpServerInfo server = servers.get(id);
        if (server == null) throw new IllegalArgumentException("unknown MCP server: " + id);
        return server;
    }

    private static String normalizeTransport(String transport) {
        String value = required(transport, "transport").toLowerCase(Locale.ROOT);
        if ("streamable-http".equals(value)) value = "streamable_http";
        if (!SUPPORTED_TRANSPORTS.contains(value)) {
            throw new IllegalArgumentException("transport must be one of: " + SUPPORTED_TRANSPORTS);
        }
        return value;
    }

    private static void validateTransport(String transport, String endpoint, String command) {
        if ("stdio".equals(transport) && blankToNull(command) == null) {
            throw new IllegalArgumentException("command is required for stdio transport");
        }
        if (!"stdio".equals(transport) && blankToNull(endpoint) == null) {
            throw new IllegalArgumentException("endpoint is required for HTTP transports");
        }
    }

    private static String required(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String blankToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }
}
