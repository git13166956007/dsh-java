package io.github.git13166956007.dsh.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpServerRegistryTest {
    @Test
    void validatesTransportRequirementsAndTracksConfiguration() {
        McpServerRegistry registry = new McpServerRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> registry.create("clock", "stdio", null, null, List.of()));

        McpServerInfo server = registry.create("clock", "stdio", null, "node", List.of("server.js"));
        assertEquals("DISCONNECTED", server.status());
        assertEquals("stdio", server.transport());
        assertEquals(1, registry.list().size());

        McpServerInfo updated = registry.update(server.id(), null, null, null, null, null, false);
        assertEquals(false, updated.enabled());
    }

    @Test
    void serverProfilesSurviveRegistryRecreation() {
        InMemoryMcpServerStore store = new InMemoryMcpServerStore();
        McpServerRegistry first = new McpServerRegistry(store);
        McpServerInfo created = first.create("saved", "stdio", null, "node", List.of("server.js"));
        first.update(created.id(), null, null, null, null, null, false);

        McpServerRegistry second = new McpServerRegistry(store);
        McpServerInfo restored = second.find(created.id());
        assertEquals(false, restored.enabled());
        assertEquals("DISCONNECTED", restored.status());
        assertEquals(List.of("server.js"), restored.arguments());
    }

    @Test
    void keepsCredentialValuesOutOfPublicServerMetadata() {
        InMemoryMcpServerStore store = new InMemoryMcpServerStore();
        McpServerRegistry registry = new McpServerRegistry(store);
        McpServerInfo created = registry.create("secured", "sse", "https://example.test/sse", null, List.of(),
                "AMAP_API_KEY", Map.of("Authorization", "Bearer secret-value"), Map.of("API_KEY", "secret-value"));

        assertEquals("AMAP_API_KEY", created.credentialRef());
        assertEquals(List.of("Authorization"), created.headerNames());
        assertEquals(List.of("API_KEY"), created.environmentNames());
        assertEquals("Bearer secret-value", registry.credentials(created.id()).headers().get("Authorization"));
        assertEquals("secret-value", registry.credentials(created.id()).environment().get("API_KEY"));
        assertEquals(false, created.toString().contains("secret-value"));

        McpServerInfo restored = new McpServerRegistry(store).find(created.id());
        assertEquals(List.of("Authorization"), restored.headerNames());
        assertEquals(List.of("API_KEY"), restored.environmentNames());
    }

    @Test
    void persistsServerToolApprovalPolicy() {
        InMemoryMcpServerStore store = new InMemoryMcpServerStore();
        McpServerRegistry first = new McpServerRegistry(store);
        McpServerInfo created = first.create("approval", "stdio", null, "node", List.of("server.js"),
                null, Map.of(), Map.of(), false);

        assertEquals(false, created.approvalRequired());
        McpServerInfo restored = new McpServerRegistry(store).find(created.id());
        assertEquals(false, restored.approvalRequired());

        McpServerInfo updated = first.update(created.id(), null, null, null, null, null, null,
                null, null, null, true);
        assertEquals(true, updated.approvalRequired());
    }
}
