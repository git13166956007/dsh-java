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
}
