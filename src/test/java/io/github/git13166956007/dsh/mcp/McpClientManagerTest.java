package io.github.git13166956007.dsh.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpClientManagerTest {
    @Test
    void splitsFullEndpointWithoutDroppingQueryParameters() {
        McpClientManager.ResolvedEndpoint endpoint = McpClientManager.resolveEndpoint(
                "https://mcp.amap.com/mcp?key=debug-key", "/sse");

        assertEquals("https://mcp.amap.com", endpoint.baseUri());
        assertEquals("/mcp?key=debug-key", endpoint.endpoint());
    }

    @Test
    void appendsResolvedQuerySecretsOnlyWhenConnecting() {
        McpClientManager.ResolvedEndpoint endpoint = McpClientManager.resolveEndpoint(
                "https://mcp.example/mcp?version=1", "/mcp", Map.of("key", "secret value"));
        assertEquals("/mcp?version=1&key=secret+value", endpoint.endpoint());
    }

    @Test
    void exposesRemoteToolNamesUsingDeepSeekCompatibleCharacters() {
        McpServerInfo server = new McpServerInfo(
                "12345678-aaaa-bbbb-cccc-dddddddddddd", "demo", "stdio", null, "node", java.util.List.of(), true, "CONNECTED");

        assertEquals("mcp_12345678_weather_lookup",
                McpClientManager.exposedName(server, "weather.lookup"));
    }

    @Test
    void calculatesCappedExponentialReconnectDelay() {
        assertEquals(1000, McpClientManager.reconnectDelay(1000, 5000, 0));
        assertEquals(2000, McpClientManager.reconnectDelay(1000, 5000, 1));
        assertEquals(4000, McpClientManager.reconnectDelay(1000, 5000, 2));
        assertEquals(5000, McpClientManager.reconnectDelay(1000, 5000, 8));
    }

    @Test
    void rejectsPrivateMcpEndpointsUnlessExplicitlyAllowed() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpEndpointPolicy(false).validateForConnection("http://127.0.0.1:8080/mcp"));
        assertDoesNotThrow(() -> new McpEndpointPolicy(true)
                .validateForConnection("http://127.0.0.1:8080/mcp"));
    }

    @Test
    void rejectsNonHttpMcpEndpointSchemes() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpEndpointPolicy(false).validateSyntax("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> new McpServerRegistry().create("invalid", "sse", "ftp://example.test/mcp",
                        null, List.of()));
    }

    @Test
    void exposesUnknownHealthForConfiguredServerBeforeFirstConnection() {
        McpServerRegistry servers = new McpServerRegistry();
        McpServerInfo server = servers.create("health", "stdio", null, "node", java.util.List.of("server.js"));
        McpClientManager manager = new McpClientManager(servers, new io.github.git13166956007.dsh.tool.ToolRegistry(),
                new tools.jackson.databind.ObjectMapper());
        try {
            assertEquals("UNKNOWN", manager.health(server.id()).status());
            assertEquals(0, manager.health(server.id()).successCount());
        } finally {
            manager.close();
        }
    }

    @Test
    void restoresPersistedResourceSubscriptionsWhenManagerIsRecreated() {
        InMemoryMcpServerStore serverStore = new InMemoryMcpServerStore();
        McpServerRegistry servers = new McpServerRegistry(serverStore);
        McpServerInfo server = servers.create("resources", "stdio", null, "node", List.of("server.js"));
        InMemoryMcpResourceSubscriptionStore subscriptions = new InMemoryMcpResourceSubscriptionStore();
        subscriptions.save(new McpResourceSubscription(server.id(), "file:///workspace/README.md", Instant.now()));

        McpClientManager first = new McpClientManager(servers, new io.github.git13166956007.dsh.tool.ToolRegistry(),
                new tools.jackson.databind.ObjectMapper(), new InMemoryMcpHealthStore(), subscriptions,
                1_000, 5_000, 0);
        try {
            assertEquals(List.of("file:///workspace/README.md"), first.subscriptions(server.id()));
        } finally {
            first.close();
        }

        McpClientManager restored = new McpClientManager(servers, new io.github.git13166956007.dsh.tool.ToolRegistry(),
                new tools.jackson.databind.ObjectMapper(), new InMemoryMcpHealthStore(), subscriptions,
                1_000, 5_000, 0);
        try {
            assertEquals(List.of("file:///workspace/README.md"), restored.subscriptions(server.id()));
        } finally {
            restored.close();
        }
        subscriptions.delete(server.id(), "file:///workspace/README.md");
        assertTrue(subscriptions.list(server.id()).isEmpty());
    }
}
