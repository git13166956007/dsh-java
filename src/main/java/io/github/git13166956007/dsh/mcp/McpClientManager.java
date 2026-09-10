package io.github.git13166956007.dsh.mcp;

import io.github.git13166956007.dsh.tool.ToolDefinition;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public final class McpClientManager implements AutoCloseable {
    private final McpServerRegistry servers;
    private final ToolRegistry tools;
    private final ObjectMapper objectMapper;
    private final Map<String, ConnectedServer> connected = new ConcurrentHashMap<String, ConnectedServer>();
    private final ExecutorService restoreExecutor = Executors.newCachedThreadPool();

    public McpClientManager(McpServerRegistry servers, ToolRegistry tools, ObjectMapper objectMapper) {
        this.servers = servers;
        this.tools = tools;
        this.objectMapper = objectMapper;
    }

    public synchronized McpServerInfo connect(String id) {
        McpServerInfo server = servers.find(id);
        if (server == null) throw new IllegalArgumentException("unknown MCP server: " + id);
        if (!server.enabled()) throw new IllegalStateException("MCP server is disabled: " + server.name());
        disconnect(id);
        String source = source(id);
        try {
            McpSyncClient client = buildClient(server);
            client.initialize();
            registerTools(server, client);
            connected.put(id, new ConnectedServer(client, source));
            return servers.setStatus(id, "CONNECTED");
        } catch (RuntimeException exception) {
            tools.removeBySource(source);
            servers.setStatus(id, "ERROR");
            throw new IllegalStateException(connectionError(server, exception), exception);
        }
    }

    public synchronized McpServerInfo refresh(String id) {
        if (servers.find(id) == null) throw new IllegalArgumentException("unknown MCP server: " + id);
        ConnectedServer connection = connected.get(id);
        if (connection == null) return connect(id);
        McpServerInfo server = servers.find(id);
        registerTools(server, connection.client());
        return servers.setStatus(id, "CONNECTED");
    }

    public synchronized void restoreEnabled() {
        for (McpServerInfo server : servers.list()) {
            if (!server.enabled()) continue;
            restoreExecutor.submit(() -> {
                try {
                    connect(server.id());
                } catch (Exception ignored) {
                    // A failed remote server must not prevent the host application from starting.
                }
            });
        }
    }

    public synchronized List<McpResourceInfo> resources(String id) {
        McpServerInfo server = requireServer(id);
        McpSyncClient client = requireClient(id);
        List<McpResourceInfo> result = new ArrayList<McpResourceInfo>();
        String cursor = null;
        do {
            McpSchema.ListResourcesResult page = cursor == null ? client.listResources() : client.listResources(cursor);
            if (page.resources() != null) {
                for (McpSchema.Resource resource : page.resources()) {
                    result.add(new McpResourceInfo(server.id(), resource.uri(), resource.name(), resource.title(),
                            resource.description(), resource.mimeType(), resource.size()));
                }
            }
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isBlank());
        return result;
    }

    public synchronized List<McpPromptInfo> prompts(String id) {
        McpServerInfo server = requireServer(id);
        McpSyncClient client = requireClient(id);
        List<McpPromptInfo> result = new ArrayList<McpPromptInfo>();
        String cursor = null;
        do {
            McpSchema.ListPromptsResult page = cursor == null ? client.listPrompts() : client.listPrompts(cursor);
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
        McpSchema.ReadResourceResult result = requireClient(id).readResource(new McpSchema.ReadResourceRequest(uri));
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
                arguments == null ? Map.of() : arguments));
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
        ConnectedServer connection = connected.remove(id);
        tools.removeBySource(source(id));
        if (connection != null) connection.client().close();
        return servers.setStatus(server.id(), "DISCONNECTED");
    }

    public synchronized void remove(String id) {
        if (servers.find(id) != null) disconnect(id);
        servers.delete(id);
    }

    private McpSyncClient buildClient(McpServerInfo server) {
        McpServerSecrets credentials = servers.credentials(server.id());
        if ("stdio".equals(server.transport())) {
            ServerParameters parameters = ServerParameters.builder(server.command()).args(server.arguments())
                    .env(resolveEnvironment(server, credentials)).build();
            StdioClientTransport transport = new StdioClientTransport(parameters, McpJsonMapper.getDefault());
            return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30))
                    .clientInfo(new McpSchema.Implementation("dsh-java", "0.1.0"))
                    .toolsChangeConsumer(updated -> refreshTools(server, updated))
                    .build();
        }
        if ("sse".equals(server.transport())) {
            ResolvedEndpoint endpoint = resolveEndpoint(server.endpoint(), "/sse");
            HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(endpoint.baseUri())
                    .sseEndpoint(endpoint.endpoint())
                    .customizeRequest(builder -> applyHeaders(builder, resolveHeaders(server, credentials))).build();
            return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30))
                    .clientInfo(new McpSchema.Implementation("dsh-java", "0.1.0"))
                    .toolsChangeConsumer(updated -> refreshTools(server, updated))
                    .build();
        }
        ResolvedEndpoint endpoint = resolveEndpoint(server.endpoint(), "/mcp");
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(endpoint.baseUri())
                .endpoint(endpoint.endpoint())
                .customizeRequest(builder -> applyHeaders(builder, resolveHeaders(server, credentials))).build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30))
                .clientInfo(new McpSchema.Implementation("dsh-java", "0.1.0"))
                .toolsChangeConsumer(updated -> refreshTools(server, updated))
                .build();
    }

    private void registerTools(McpServerInfo server, McpSyncClient client) {
        refreshTools(server, client.listTools().tools());
    }

    private void refreshTools(McpServerInfo server, List<McpSchema.Tool> remoteTools) {
        String source = source(server.id());
        tools.removeBySource(source);
        for (McpSchema.Tool remote : remoteTools) {
            String exposedName = exposedName(server, remote.name());
            tools.registerExternal(new ToolDefinition(exposedName, description(server, remote), schema(remote)),
                    arguments -> call(clientFor(server.id()), remote.name(), arguments), source);
        }
    }

    private String call(McpSyncClient client, String remoteName, tools.jackson.databind.JsonNode arguments) {
        try {
            String json = objectMapper.writeValueAsString(arguments);
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    McpJsonMapper.getDefault(), remoteName, json));
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
            throw new IllegalStateException("MCP tool call failed: " + exception.getMessage(), exception);
        }
    }

    private McpSyncClient clientFor(String id) {
        return requireClient(id);
    }

    private McpServerInfo requireServer(String id) {
        McpServerInfo server = servers.find(id);
        if (server == null) throw new IllegalArgumentException("unknown MCP server: " + id);
        return server;
    }

    private McpSyncClient requireClient(String id) {
        ConnectedServer connection = connected.get(id);
        if (connection == null) throw new IllegalStateException("MCP server is not connected: " + id);
        return connection.client();
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

    record ResolvedEndpoint(String baseUri, String endpoint) { }

    @Override
    public synchronized void close() {
        restoreExecutor.shutdownNow();
        for (String id : new ArrayList<String>(connected.keySet())) {
            try { disconnect(id); } catch (Exception ignored) { }
        }
    }

    private record ConnectedServer(McpSyncClient client, String source) { }
}
