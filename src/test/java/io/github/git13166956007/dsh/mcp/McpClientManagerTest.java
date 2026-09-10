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
}
