package io.github.git13166956007.dsh.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
