package io.github.git13166956007.dsh.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class McpServerRegistry {
    private static final List<String> SUPPORTED_TRANSPORTS = List.of("stdio", "sse", "streamable_http");
    private final McpServerStore store;
    private final Map<String, McpServerInfo> servers = new LinkedHashMap<String, McpServerInfo>();
    private final Map<String, McpServerSecrets> secrets = new LinkedHashMap<String, McpServerSecrets>();

    public McpServerRegistry() {
        this(null);
    }

    public McpServerRegistry(McpServerStore store) {
        this.store = store;
        if (store != null) {
            reloadFromStore();
        }
    }

    public synchronized McpServerInfo create(String name, String transport, String endpoint,
                                              String command, List<String> arguments) {
        return create(name, transport, endpoint, command, arguments, null, Map.of(), Map.of(), true);
    }

    public synchronized McpServerInfo create(String name, String transport, String endpoint,
                                              String command, List<String> arguments, String credentialRef,
                                              Map<String, String> headers, Map<String, String> environment) {
        return create(name, transport, endpoint, command, arguments, credentialRef, headers, environment, true);
    }

    public synchronized McpServerInfo create(String name, String transport, String endpoint,
                                              String command, List<String> arguments, String credentialRef,
                                              Map<String, String> headers, Map<String, String> environment,
                                              Boolean approvalRequired) {
        reloadFromStore();
        String normalizedName = required(name, "name");
        String normalizedTransport = normalizeTransport(transport);
        validateTransport(normalizedTransport, endpoint, command);
        if (servers.values().stream().anyMatch(server -> server.name().equalsIgnoreCase(normalizedName))) {
            throw new IllegalArgumentException("duplicate MCP server: " + normalizedName);
        }
        McpServerInfo server = new McpServerInfo(UUID.randomUUID().toString(), normalizedName,
                normalizedTransport, blankToNull(endpoint), blankToNull(command), arguments, true,
                approvalRequired == null || approvalRequired, "DISCONNECTED",
                blankToNull(credentialRef), names(headers), names(environment));
        save(server, new McpServerSecrets(headers, environment));
        return server;
    }

    public synchronized List<McpServerInfo> list() {
        reloadFromStore();
        return new ArrayList<McpServerInfo>(servers.values());
    }

    public synchronized McpServerInfo find(String id) {
        reloadFromStore();
        return servers.get(id);
    }

    public synchronized McpServerInfo update(String id, String name, String transport, String endpoint,
                                              String command, List<String> arguments, Boolean enabled) {
        return update(id, name, transport, endpoint, command, arguments, enabled, null, null, null);
    }

    public synchronized McpServerInfo update(String id, String name, String transport, String endpoint,
                                              String command, List<String> arguments, Boolean enabled,
                                              String credentialRef, Map<String, String> headers,
                                              Map<String, String> environment) {
        return update(id, name, transport, endpoint, command, arguments, enabled, credentialRef, headers, environment,
                null);
    }

    public synchronized McpServerInfo update(String id, String name, String transport, String endpoint,
                                              String command, List<String> arguments, Boolean enabled,
                                              String credentialRef, Map<String, String> headers,
                                              Map<String, String> environment, Boolean approvalRequired) {
        reloadFromStore();
        McpServerInfo current = require(id);
        McpServerSecrets currentSecrets = secrets.getOrDefault(id, McpServerSecrets.empty());
        McpServerSecrets nextSecrets = new McpServerSecrets(
                headers == null ? currentSecrets.headers() : headers,
                environment == null ? currentSecrets.environment() : environment);
        String nextName = name == null ? current.name() : required(name, "name");
        String nextTransport = transport == null ? current.transport() : normalizeTransport(transport);
        String nextEndpoint = endpoint == null ? current.endpoint() : blankToNull(endpoint);
        String nextCommand = command == null ? current.command() : blankToNull(command);
        validateTransport(nextTransport, nextEndpoint, nextCommand);
        if (servers.values().stream().anyMatch(server -> !server.id().equals(id)
                && server.name().equalsIgnoreCase(nextName))) {
            throw new IllegalArgumentException("duplicate MCP server: " + nextName);
        }
        String nextCredentialRef = credentialRef == null ? current.credentialRef() : blankToNull(credentialRef);
        McpServerInfo updated = new McpServerInfo(id, nextName, nextTransport, nextEndpoint, nextCommand,
                arguments == null ? current.arguments() : arguments,
                enabled == null ? current.enabled() : enabled,
                approvalRequired == null ? current.approvalRequired() : approvalRequired,
                "DISCONNECTED", nextCredentialRef,
                names(nextSecrets.headers()), names(nextSecrets.environment()));
        save(updated, nextSecrets);
        return updated;
    }

    public synchronized boolean delete(String id) {
        reloadFromStore();
        if (!servers.containsKey(id)) return false;
        try {
            if (store != null) store.delete(id);
            servers.remove(id);
            secrets.remove(id);
            return true;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to delete MCP server profile", exception);
        }
    }

    public synchronized McpServerInfo setStatus(String id, String status) {
        reloadFromStore();
        McpServerInfo current = require(id);
        McpServerInfo updated = new McpServerInfo(current.id(), current.name(), current.transport(),
                current.endpoint(), current.command(), current.arguments(), current.enabled(), current.approvalRequired(), status,
                current.credentialRef(), current.headerNames(), current.environmentNames());
        save(updated, secrets.getOrDefault(id, McpServerSecrets.empty()));
        return updated;
    }

    public synchronized McpServerSecrets credentials(String id) {
        reloadFromStore();
        require(id);
        return secrets.getOrDefault(id, McpServerSecrets.empty());
    }

    private void reloadFromStore() {
        if (store == null) return;
        try {
            Map<String, McpServerInfo> persisted = new LinkedHashMap<String, McpServerInfo>();
            Map<String, McpServerSecrets> persistedSecrets = new LinkedHashMap<String, McpServerSecrets>();
            for (McpServerInfo server : store.list()) {
                McpServerSecrets value = store.loadSecrets(server.id());
                McpServerInfo current = servers.get(server.id());
                String status = current == null ? server.status() : current.status();
                persisted.put(server.id(), withSecretMetadata(server, value, status));
                persistedSecrets.put(server.id(), value);
            }
            servers.clear();
            servers.putAll(persisted);
            secrets.clear();
            secrets.putAll(persistedSecrets);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load MCP server profiles", exception);
        }
    }

    private void save(McpServerInfo server, McpServerSecrets serverSecrets) {
        try {
            if (store != null) store.save(server);
            if (store != null) store.saveSecrets(server.id(), serverSecrets);
            secrets.put(server.id(), serverSecrets);
            servers.put(server.id(), server);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to save MCP server profile", exception);
        }
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

    private static List<String> names(Map<String, String> values) {
        return values == null ? List.of() : values.keySet().stream()
                .filter(key -> key != null && !key.isBlank()).map(String::trim).distinct().toList();
    }

    private static McpServerInfo withSecretMetadata(McpServerInfo server, McpServerSecrets value, String status) {
        return new McpServerInfo(server.id(), server.name(), server.transport(), server.endpoint(), server.command(),
                server.arguments(), server.enabled(), server.approvalRequired(), status, server.credentialRef(),
                names(value.headers()), names(value.environment()));
    }
}
