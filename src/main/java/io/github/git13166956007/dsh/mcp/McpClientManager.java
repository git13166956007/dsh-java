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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public final class McpClientManager implements AutoCloseable {
    private final McpServerRegistry servers;
    private final ToolRegistry tools;
    private final ObjectMapper objectMapper;
    private final Map<String, ConnectedServer> connected = new ConcurrentHashMap<String, ConnectedServer>();

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
            throw exception;
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
        if ("stdio".equals(server.transport())) {
            ServerParameters parameters = ServerParameters.builder(server.command()).args(server.arguments()).build();
            StdioClientTransport transport = new StdioClientTransport(parameters, McpJsonMapper.getDefault());
            return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30))
                    .clientInfo(new McpSchema.Implementation("dsh-java", "0.1.0"))
                    .toolsChangeConsumer(updated -> refreshTools(server, updated))
                    .build();
        }
        if ("sse".equals(server.transport())) {
            HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(server.endpoint()).build();
            return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30))
                    .clientInfo(new McpSchema.Implementation("dsh-java", "0.1.0"))
                    .toolsChangeConsumer(updated -> refreshTools(server, updated))
                    .build();
        }
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(server.endpoint()).build();
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

    private static String exposedName(McpServerInfo server, String remoteName) {
        String normalized = remoteName.replaceAll("[^A-Za-z0-9_.-]", "_");
        String prefix = "mcp." + server.id().replace("-", "").substring(0, 8) + ".";
        String result = prefix + normalized;
        if (result.length() <= 64) return result;
        return result.substring(0, 55) + "_" + Integer.toHexString(remoteName.hashCode());
    }

    private static String source(String id) {
        return "mcp:" + id;
    }

    @Override
    public synchronized void close() {
        for (String id : new ArrayList<String>(connected.keySet())) {
            try { disconnect(id); } catch (Exception ignored) { }
        }
    }

    private record ConnectedServer(McpSyncClient client, String source) { }
}
