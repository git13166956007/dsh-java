package io.github.git13166956007.dsh.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
