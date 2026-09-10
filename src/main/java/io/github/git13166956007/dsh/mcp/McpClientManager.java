package io.github.git13166956007.dsh.mcp;

import io.github.git13166956007.dsh.tool.ToolDefinition;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Mono;

public final class McpClientManager implements AutoCloseable {
    private final McpServerRegistry servers;
    private final ToolRegistry tools;
    private final ObjectMapper objectMapper;
    private final McpHealthStore healthStore;
    private final McpResourceSubscriptionStore subscriptionStore;
    private final Map<String, ConnectedServer> connected = new ConcurrentHashMap<String, ConnectedServer>();
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<String, Set<String>>();
    private final Map<String, Map<String, McpResourceUpdate>> resourceUpdates = new ConcurrentHashMap<String, Map<String, McpResourceUpdate>>();
    private final Map<String, List<McpResourceInfo>> resourceCatalog = new ConcurrentHashMap<String, List<McpResourceInfo>>();
    private final ExecutorService restoreExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService reconnectExecutor = Executors.newScheduledThreadPool(1);
    private final Map<String, ScheduledFuture<?>> reconnects = new ConcurrentHashMap<String, ScheduledFuture<?>>();
    private final long reconnectInitialDelayMs;
    private final long reconnectMaxDelayMs;
    private final int reconnectMaxAttempts;
    private volatile boolean closed;

    public McpClientManager(McpServerRegistry servers, ToolRegistry tools, ObjectMapper objectMapper) {
        this(servers, tools, objectMapper, new InMemoryMcpHealthStore(), new InMemoryMcpResourceSubscriptionStore(),
                1_000, 60_000, 8);
    }

    public McpClientManager(McpServerRegistry servers, ToolRegistry tools, ObjectMapper objectMapper,
                            long reconnectInitialDelayMs, long reconnectMaxDelayMs, int reconnectMaxAttempts) {
        this(servers, tools, objectMapper, new InMemoryMcpHealthStore(), reconnectInitialDelayMs,
                reconnectMaxDelayMs, reconnectMaxAttempts);
    }

    public McpClientManager(McpServerRegistry servers, ToolRegistry tools, ObjectMapper objectMapper,
                            McpHealthStore healthStore, long reconnectInitialDelayMs, long reconnectMaxDelayMs,
                            int reconnectMaxAttempts) {
        this(servers, tools, objectMapper, healthStore, new InMemoryMcpResourceSubscriptionStore(),
                reconnectInitialDelayMs, reconnectMaxDelayMs, reconnectMaxAttempts);
    }

    public McpClientManager(McpServerRegistry servers, ToolRegistry tools, ObjectMapper objectMapper,
                            McpHealthStore healthStore, McpResourceSubscriptionStore subscriptionStore,
                            long reconnectInitialDelayMs, long reconnectMaxDelayMs, int reconnectMaxAttempts) {
        if (reconnectInitialDelayMs < 1 || reconnectMaxDelayMs < reconnectInitialDelayMs) {
            throw new IllegalArgumentException("invalid MCP reconnect delay configuration");
        }
        if (reconnectMaxAttempts < 0) throw new IllegalArgumentException("reconnectMaxAttempts must not be negative");
        this.servers = servers;
        this.tools = tools;
        this.objectMapper = objectMapper;
        this.healthStore = healthStore;
        this.subscriptionStore = subscriptionStore;
        this.reconnectInitialDelayMs = reconnectInitialDelayMs;
        this.reconnectMaxDelayMs = reconnectMaxDelayMs;
        this.reconnectMaxAttempts = reconnectMaxAttempts;
        restoreSubscriptionState();
    }

    public synchronized McpServerInfo connect(String id) {
        McpServerInfo server = servers.find(id);
        if (server == null) throw new IllegalArgumentException("unknown MCP server: " + id);
        if (!server.enabled()) throw new IllegalStateException("MCP server is disabled: " + server.name());
        cancelReconnect(id);
        disconnect(id);
        String source = source(id);
        McpAsyncClient client = null;
        long started = System.nanoTime();
        try {
            client = buildClient(server);
            client.initialize().block();
            registerTools(server, client);
            connected.put(id, new ConnectedServer(client, source));
            restoreSubscriptions(server.id(), client);
            reconnects.remove(id);
            recordSuccess(id, elapsedMs(started));
            return servers.setStatus(id, "CONNECTED");
        } catch (RuntimeException exception) {
            if (client != null) client.close();
            tools.removeBySource(source);
            recordFailure(id, elapsedMs(started), exception);
            servers.setStatus(id, "ERROR");
            throw new IllegalStateException(connectionError(server, exception), exception);
        }
    }

    public synchronized McpServerInfo refresh(String id) {
        if (servers.find(id) == null) throw new IllegalArgumentException("unknown MCP server: " + id);
        ConnectedServer connection = connected.get(id);
        if (connection == null) return connect(id);
        McpServerInfo server = servers.find(id);
        long started = System.nanoTime();
        try {
            registerTools(server, connection.client());
            recordSuccess(id, elapsedMs(started));
            return servers.setStatus(id, "CONNECTED");
        } catch (RuntimeException exception) {
            recordFailure(id, elapsedMs(started), exception);
            throw exception;
        }
    }

    public synchronized void restoreEnabled() {
        for (McpServerInfo server : servers.list()) {
            if (!server.enabled()) continue;
            restoreExecutor.submit(() -> attemptRestore(server.id(), 0));
        }
    }

    public synchronized List<McpResourceInfo> resources(String id) {
        McpServerInfo server = requireServer(id);
        McpAsyncClient client = requireClient(id);
        List<McpResourceInfo> result = new ArrayList<McpResourceInfo>();
        String cursor = null;
        do {
            McpSchema.ListResourcesResult page = (cursor == null ? client.listResources() : client.listResources(cursor)).block();
            if (page.resources() != null) {
                for (McpSchema.Resource resource : page.resources()) {
                    result.add(new McpResourceInfo(server.id(), resource.uri(), resource.name(), resource.title(),
                            resource.description(), resource.mimeType(), resource.size()));
                }
            }
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isBlank());
        resourceCatalog.put(id, List.copyOf(result));
        return result;
    }

    public synchronized List<McpPromptInfo> prompts(String id) {
        McpServerInfo server = requireServer(id);
        McpAsyncClient client = requireClient(id);
        List<McpPromptInfo> result = new ArrayList<McpPromptInfo>();
        String cursor = null;
        do {
            McpSchema.ListPromptsResult page = (cursor == null ? client.listPrompts() : client.listPrompts(cursor)).block();
            if (page.prompts() != null) {
                for (McpSchema.Prompt prompt : page.prompts()) {
                    result.add(new McpPromptInfo(server.id(), prompt.name(), prompt.title(), prompt.description(),
                            prompt.arguments() == null ? List.of() : prompt.arguments().stream()
                                    .map(argument -> argument.name()).toList()));
                }
            }
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isBlank());
        return result;
    }

    public synchronized List<McpResourceContent> readResource(String id, String uri) {
        requireServer(id);
        McpSchema.ReadResourceResult result = requireClient(id)
                .readResource(new McpSchema.ReadResourceRequest(uri)).block();
        List<McpResourceContent> content = new ArrayList<McpResourceContent>();
        if (result.contents() != null) {
            for (McpSchema.ResourceContents item : result.contents()) {
                if (item instanceof McpSchema.TextResourceContents text) {
                    content.add(new McpResourceContent(text.uri(), text.mimeType(), text.text(), null));
                } else if (item instanceof McpSchema.BlobResourceContents blob) {
                    content.add(new McpResourceContent(blob.uri(), blob.mimeType(), null, blob.blob()));
                }
            }
        }
        return content;
    }

    public synchronized McpPromptResult getPrompt(String id, String name, Map<String, Object> arguments) {
        requireServer(id);
        McpSchema.GetPromptResult result = requireClient(id).getPrompt(new McpSchema.GetPromptRequest(name,
                arguments == null ? Map.of() : arguments)).block();
        List<McpPromptMessage> messages = new ArrayList<McpPromptMessage>();
        if (result.messages() != null) {
            for (McpSchema.PromptMessage message : result.messages()) {
                String text = message.content() instanceof McpSchema.TextContent value ? value.text() : null;
                messages.add(new McpPromptMessage(message.role() == null ? null : message.role().name().toLowerCase(), text));
            }
        }
        return new McpPromptResult(result.description(), messages);
    }

    public synchronized McpServerInfo disconnect(String id) {
        McpServerInfo server = servers.find(id);
        if (server == null) throw new IllegalArgumentException("unknown MCP server: " + id);
        cancelReconnect(id);
        ConnectedServer connection = connected.remove(id);
        tools.removeBySource(source(id));
        resourceCatalog.remove(id);
        resourceUpdates.remove(id);
        if (connection != null) connection.client().close();
        if (connection != null) recordDisconnected(id);
        return servers.setStatus(server.id(), "DISCONNECTED");
    }

    public synchronized void remove(String id) {
        if (servers.find(id) != null) disconnect(id);
        subscriptions.remove(id);
        try {
            subscriptionStore.deleteServer(id);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to delete MCP resource subscriptions", exception);
        }
        resourceUpdates.remove(id);
        resourceCatalog.remove(id);
        servers.delete(id);
        try {
            healthStore.delete(id);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to delete MCP health", exception);
        }
    }

    public synchronized McpResourceSubscription subscribeResource(String id, String uri) {
        requireServer(id);
        String normalizedUri = requiredUri(uri);
        McpAsyncClient client = requireClient(id);
        client.subscribeResource(new McpSchema.SubscribeRequest(normalizedUri)).block();
        McpResourceSubscription subscription = new McpResourceSubscription(id, normalizedUri, java.time.Instant.now());
        try {
            subscriptionStore.save(subscription);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to persist MCP resource subscription", exception);
        }
        subscriptions.computeIfAbsent(id, ignored -> ConcurrentHashMap.newKeySet()).add(normalizedUri);
        return subscription;
    }

    public synchronized McpResourceSubscription unsubscribeResource(String id, String uri) {
        requireServer(id);
        String normalizedUri = requiredUri(uri);
        requireClient(id).unsubscribeResource(new McpSchema.UnsubscribeRequest(normalizedUri)).block();
        try {
            subscriptionStore.delete(id, normalizedUri);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to delete MCP resource subscription", exception);
        }
        Set<String> values = subscriptions.get(id);
        if (values != null) {
            values.remove(normalizedUri);
            if (values.isEmpty()) subscriptions.remove(id);
        }
        return new McpResourceSubscription(id, normalizedUri, java.time.Instant.now());
    }

    public synchronized List<String> subscriptions(String id) {
        requireServer(id);
        return subscriptions.getOrDefault(id, Set.of()).stream().sorted().toList();
    }

    public synchronized List<McpResourceUpdate> resourceUpdates(String id) {
        requireServer(id);
        return resourceUpdates.getOrDefault(id, Map.of()).values().stream()
                .sorted(java.util.Comparator.comparing(McpResourceUpdate::uri)).toList();
    }

    private McpAsyncClient buildClient(McpServerInfo server) {
        McpServerSecrets credentials = servers.credentials(server.id());
        if ("stdio".equals(server.transport())) {
            ServerParameters parameters = ServerParameters.builder(server.command()).args(server.arguments())
                    .env(resolveEnvironment(server, credentials)).build();
            StdioClientTransport transport = new StdioClientTransport(parameters, McpJsonMapper.getDefault());
            return McpClient.async(transport).requestTimeout(Duration.ofSeconds(30))
                    .clientInfo(new McpSchema.Implementation("dsh-java", "0.1.0"))
                    .toolsChangeConsumer(updated -> Mono.fromRunnable(() -> refreshTools(server, updated)))
                    .resourcesChangeConsumer(updated -> Mono.fromRunnable(() -> refreshResourceCatalog(server, updated)))
                    .resourcesUpdateConsumer(updated -> Mono.fromRunnable(() -> resourceUpdated(server, updated)))
                    .build();
        }
        if ("sse".equals(server.transport())) {
            ResolvedEndpoint endpoint = resolveEndpoint(server.endpoint(), "/sse");
            HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(endpoint.baseUri())
                    .sseEndpoint(endpoint.endpoint())
                    .customizeRequest(builder -> applyHeaders(builder, resolveHeaders(server, credentials))).build();
            return McpClient.async(transport).requestTimeout(Duration.ofSeconds(30))
                    .clientInfo(new McpSchema.Implementation("dsh-java", "0.1.0"))
                    .toolsChangeConsumer(updated -> Mono.fromRunnable(() -> refreshTools(server, updated)))
                    .resourcesChangeConsumer(updated -> Mono.fromRunnable(() -> refreshResourceCatalog(server, updated)))
                    .resourcesUpdateConsumer(updated -> Mono.fromRunnable(() -> resourceUpdated(server, updated)))
                    .build();
        }
        ResolvedEndpoint endpoint = resolveEndpoint(server.endpoint(), "/mcp");
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(endpoint.baseUri())
                .endpoint(endpoint.endpoint())
                .customizeRequest(builder -> applyHeaders(builder, resolveHeaders(server, credentials))).build();
        return McpClient.async(transport).requestTimeout(Duration.ofSeconds(30))
                .clientInfo(new McpSchema.Implementation("dsh-java", "0.1.0"))
                .toolsChangeConsumer(updated -> Mono.fromRunnable(() -> refreshTools(server, updated)))
                .resourcesChangeConsumer(updated -> Mono.fromRunnable(() -> refreshResourceCatalog(server, updated)))
                .resourcesUpdateConsumer(updated -> Mono.fromRunnable(() -> resourceUpdated(server, updated)))
                .build();
    }

    private void registerTools(McpServerInfo server, McpAsyncClient client) {
        McpSchema.ListToolsResult result = client.listTools().block();
        refreshTools(server, result == null ? List.of() : result.tools());
    }

    private void restoreSubscriptions(String id, McpAsyncClient client) {
        for (String uri : subscriptions.getOrDefault(id, Set.of())) {
            client.subscribeResource(new McpSchema.SubscribeRequest(uri)).block();
        }
    }

    private void restoreSubscriptionState() {
        for (McpServerInfo server : servers.list()) {
            loadSubscriptions(server.id());
        }
    }

    private void loadSubscriptions(String id) {
        try {
            Set<String> values = subscriptions.computeIfAbsent(id, ignored -> ConcurrentHashMap.newKeySet());
            for (McpResourceSubscription subscription : subscriptionStore.list(id)) {
                if (subscription.uri() != null && !subscription.uri().isBlank()) values.add(subscription.uri());
            }
            if (values.isEmpty()) subscriptions.remove(id);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load MCP resource subscriptions", exception);
        }
    }

    private void refreshResourceCatalog(McpServerInfo server, List<McpSchema.Resource> resources) {
        List<McpResourceInfo> result = new ArrayList<McpResourceInfo>();
        if (resources != null) {
            for (McpSchema.Resource resource : resources) {
                result.add(new McpResourceInfo(server.id(), resource.uri(), resource.name(), resource.title(),
                        resource.description(), resource.mimeType(), resource.size()));
            }
        }
        resourceCatalog.put(server.id(), List.copyOf(result));
    }

    private void resourceUpdated(McpServerInfo server, List<McpSchema.ResourceContents> contents) {
        if (contents == null || contents.isEmpty()) return;
        String uri = contents.get(0).uri();
        if (uri == null || uri.isBlank()) return;
        resourceUpdates.computeIfAbsent(server.id(), ignored -> new ConcurrentHashMap<String, McpResourceUpdate>())
                .put(uri, new McpResourceUpdate(server.id(), uri, resourceContents(contents), java.time.Instant.now()));
    }

    private List<McpResourceContent> resourceContents(List<McpSchema.ResourceContents> contents) {
        List<McpResourceContent> result = new ArrayList<McpResourceContent>();
        for (McpSchema.ResourceContents item : contents) {
            if (item instanceof McpSchema.TextResourceContents text) {
                result.add(new McpResourceContent(text.uri(), text.mimeType(), text.text(), null));
            } else if (item instanceof McpSchema.BlobResourceContents blob) {
                result.add(new McpResourceContent(blob.uri(), blob.mimeType(), null, blob.blob()));
            }
        }
        return result;
    }

    private void refreshTools(McpServerInfo server, List<McpSchema.Tool> remoteTools) {
        String source = source(server.id());
        tools.removeBySource(source);
        for (McpSchema.Tool remote : remoteTools) {
            String exposedName = exposedName(server, remote.name());
            tools.registerExternal(new ToolDefinition(exposedName, description(server, remote), schema(remote)),
                    arguments -> call(clientFor(server.id()), remote.name(), arguments), source, server.approvalRequired());
        }
    }

    private String call(McpAsyncClient client, String remoteName, tools.jackson.databind.JsonNode arguments) {
        try {
            String json = objectMapper.writeValueAsString(arguments);
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    McpJsonMapper.getDefault(), remoteName, json)).block();
            List<String> text = new ArrayList<String>();
            if (result.content() != null) {
                for (McpSchema.Content item : result.content()) {
                    if (item instanceof McpSchema.TextContent textContent) text.add(textContent.text());
                }
            }
            if (result.structuredContent() != null) text.add(objectMapper.writeValueAsString(result.structuredContent()));
            String output = String.join("\n", text);
            if (Boolean.TRUE.equals(result.isError())) return "MCP tool error: " + output;
            return output;
        } catch (Exception exception) {
            ConnectedServer failed = connected.values().stream().filter(value -> value.client() == client).findFirst().orElse(null);
            if (failed != null) scheduleReconnect(failed.source().substring("mcp:".length()), 0);
            throw new IllegalStateException("MCP tool call failed: " + exception.getMessage(), exception);
        }
    }

    private McpAsyncClient clientFor(String id) {
        return requireClient(id);
    }

    private McpServerInfo requireServer(String id) {
        McpServerInfo server = servers.find(id);
        if (server == null) throw new IllegalArgumentException("unknown MCP server: " + id);
        return server;
    }

    private McpAsyncClient requireClient(String id) {
        ConnectedServer connection = connected.get(id);
        if (connection == null) throw new IllegalStateException("MCP server is not connected: " + id);
        return connection.client();
    }

    private static String requiredUri(String uri) {
        if (uri == null || uri.trim().isEmpty()) throw new IllegalArgumentException("resource uri must not be blank");
        return uri.trim();
    }

    private ObjectNode schema(McpSchema.Tool tool) {
        ObjectNode node = objectMapper.createObjectNode();
        McpSchema.JsonSchema schema = tool.inputSchema();
        node.put("type", schema == null || schema.type() == null ? "object" : schema.type());
        if (schema != null && schema.properties() != null) node.set("properties", objectMapper.readTree(objectMapper.writeValueAsString(schema.properties())));
        if (schema != null && schema.required() != null) node.set("required", objectMapper.readTree(objectMapper.writeValueAsString(schema.required())));
        return node;
    }

    private static String description(McpServerInfo server, McpSchema.Tool tool) {
        String description = tool.description() == null ? "MCP tool" : tool.description();
        return "[" + server.name() + "] " + description;
    }

    static String exposedName(McpServerInfo server, String remoteName) {
        String normalized = remoteName.replaceAll("[^A-Za-z0-9_-]", "_");
        String prefix = "mcp_" + server.id().replace("-", "").substring(0, 8) + "_";
        String result = prefix + normalized;
        if (result.length() <= 64) return result;
        return result.substring(0, 55) + "_" + Integer.toHexString(remoteName.hashCode());
    }

    private static String source(String id) {
        return "mcp:" + id;
    }

    private Map<String, String> resolveHeaders(McpServerInfo server, McpServerSecrets credentials) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        credentials.headers().forEach((name, value) -> result.put(name, resolveValue(value)));
        if (server.credentialRef() != null && !result.keySet().stream()
                .anyMatch(name -> "authorization".equalsIgnoreCase(name))) {
            result.put("Authorization", "Bearer " + resolveReference(server.credentialRef()));
        }
        return result;
    }

    private Map<String, String> resolveEnvironment(McpServerInfo server, McpServerSecrets credentials) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        credentials.environment().forEach((name, value) -> result.put(name, resolveValue(value)));
        if (server.credentialRef() != null && !result.containsKey("MCP_API_KEY")) {
            result.put("MCP_API_KEY", resolveReference(server.credentialRef()));
        }
        return result;
    }

    private static void applyHeaders(java.net.http.HttpRequest.Builder builder, Map<String, String> headers) {
        headers.forEach(builder::header);
    }

    private static String resolveValue(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.startsWith("env:")) return resolveReference(trimmed.substring(4));
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            return resolveReference(trimmed.substring(2, trimmed.length() - 1));
        }
        return value;
    }

    private static String resolveReference(String reference) {
        String name = reference == null ? "" : reference.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("MCP credential reference must not be blank");
        String value = System.getenv(name);
        if (value == null || value.isBlank()) value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("MCP credential reference is not configured: " + name);
        }
        return value;
    }

    static ResolvedEndpoint resolveEndpoint(String endpoint, String defaultPath) {
        try {
            URI uri = URI.create(endpoint.trim());
            if (!uri.isAbsolute() || uri.getHost() == null) {
                throw new IllegalArgumentException("MCP endpoint must be an absolute URL");
            }
            String path = uri.getRawPath();
            if (path == null || path.isBlank() || "/".equals(path)) path = defaultPath;
            if (!path.startsWith("/")) path = "/" + path;
            if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) path += "?" + uri.getRawQuery();
            return new ResolvedEndpoint(uri.getScheme() + "://" + uri.getRawAuthority(), path);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid MCP endpoint: " + endpoint, exception);
        }
    }

    private static String connectionError(McpServerInfo server, RuntimeException exception) {
        String message = rootMessage(exception);
        if (message.contains("INVALID_USER_KEY")) {
            return "MCP server rejected the API key (INVALID_USER_KEY). Check the key and its service permissions."
                    + (server.endpoint() != null && server.endpoint().contains("amap.com")
                    ? " For AMap, use transport streamable_http with the full URL https://mcp.amap.com/mcp?key=YOUR_KEY."
                    : "");
        }
        if (message.contains("Invalid SSE response")) {
            return "MCP endpoint returned a non-SSE response. Check the transport and endpoint path."
                    + (server.endpoint() != null && server.endpoint().contains("amap.com")
                    ? " AMap's current MCP endpoint uses Streamable HTTP; select streamable_http and use /mcp?key=YOUR_KEY."
                    : "");
        }
        return "MCP connection failed: " + message;
    }

    private static String rootMessage(Throwable exception) {
        Throwable current = exception;
        String message = exception.getMessage();
        while (current.getCause() != null) {
            current = current.getCause();
            if (current.getMessage() != null) message = current.getMessage();
        }
        return message == null ? exception.getClass().getSimpleName() : message;
    }

    public synchronized McpHealth health(String id) {
        if (servers.find(id) == null) throw new IllegalArgumentException("unknown MCP server: " + id);
        try {
            McpHealthData value = healthStore.find(id);
            return value == null ? McpHealth.unknown(id) : McpHealth.from(value);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load MCP health", exception);
        }
    }

    private void recordSuccess(String id, long latencyMs) {
        try {
            McpHealthData previous = healthStore.find(id);
            long successes = previous == null ? 0 : previous.successCount();
            long failures = previous == null ? 0 : previous.failureCount();
            java.time.Instant now = java.time.Instant.now();
            healthStore.save(new McpHealthData(id, "HEALTHY", successes + 1, failures, Math.max(0, latencyMs),
                    now, now, previous == null ? null : previous.lastDisconnectedAt(), null));
        } catch (Exception ignored) {
        }
    }

    private void recordFailure(String id, long latencyMs, Throwable failure) {
        try {
            McpHealthData previous = healthStore.find(id);
            long successes = previous == null ? 0 : previous.successCount();
            long failures = previous == null ? 0 : previous.failureCount();
            java.time.Instant now = java.time.Instant.now();
            String message = failure == null ? "MCP operation failed" : failure.getMessage();
            healthStore.save(new McpHealthData(id, "UNHEALTHY", successes, failures + 1, Math.max(0, latencyMs),
                    now, previous == null ? null : previous.lastConnectedAt(),
                    previous == null ? null : previous.lastDisconnectedAt(),
                    message == null ? failure.getClass().getSimpleName() : message));
        } catch (Exception ignored) {
        }
    }

    private void recordDisconnected(String id) {
        try {
            McpHealthData previous = healthStore.find(id);
            java.time.Instant now = java.time.Instant.now();
            healthStore.save(new McpHealthData(id, previous == null ? "UNKNOWN" : previous.status(),
                    previous == null ? 0 : previous.successCount(), previous == null ? 0 : previous.failureCount(),
                    previous == null ? null : previous.lastLatencyMs(),
                    previous == null ? null : previous.lastCheckedAt(),
                    previous == null ? null : previous.lastConnectedAt(), now,
                    previous == null ? null : previous.lastError()));
        } catch (Exception ignored) {
        }
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    record ResolvedEndpoint(String baseUri, String endpoint) { }

    @Override
    public synchronized void close() {
        closed = true;
        reconnects.values().forEach(future -> future.cancel(false));
        reconnects.clear();
        reconnectExecutor.shutdownNow();
        restoreExecutor.shutdownNow();
        for (String id : new ArrayList<String>(connected.keySet())) {
            try { disconnect(id); } catch (Exception ignored) { }
        }
    }

    private record ConnectedServer(McpAsyncClient client, String source) { }

    private void attemptRestore(String id, int attempt) {
        if (closed) return;
        McpServerInfo server = servers.find(id);
        if (server == null || !server.enabled()) return;
        try {
            connect(id);
        } catch (Exception ignored) {
            if (attempt < reconnectMaxAttempts && !closed && servers.find(id) != null
                    && servers.find(id).enabled()) {
                scheduleReconnect(id, attempt + 1);
            }
        }
    }

    private void scheduleReconnect(String id, int attempt) {
        if (closed || reconnects.containsKey(id)) return;
        long delay = reconnectDelay(reconnectInitialDelayMs, reconnectMaxDelayMs, attempt);
        ScheduledFuture<?> future = reconnectExecutor.schedule(() -> {
            reconnects.remove(id);
            attemptRestore(id, attempt);
        }, delay, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = reconnects.putIfAbsent(id, future);
        if (previous != null) future.cancel(false);
    }

    private void cancelReconnect(String id) {
        ScheduledFuture<?> future = reconnects.remove(id);
        if (future != null) future.cancel(false);
    }

    static long reconnectDelay(long initialDelayMs, long maxDelayMs, int attempt) {
        long delay = initialDelayMs;
        for (int index = 0; index < attempt && delay < maxDelayMs; index++) {
            delay = Math.min(maxDelayMs, delay > maxDelayMs / 2 ? maxDelayMs : delay * 2);
        }
        return delay;
    }
}
