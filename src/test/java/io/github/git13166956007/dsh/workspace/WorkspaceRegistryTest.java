package io.github.git13166956007.dsh.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class WorkspaceRegistryTest {
    @TempDir
    Path root;

    @Test
    void seedsAndSwitchesActiveWorkspace() {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        WorkspaceRegistry registry = new WorkspaceRegistry(store, root.toString(), 1000, 2000,
                false, 60, 3000, Set.of("git"));

        assertEquals("default", registry.active().id());
        WorkspaceProfile second = registry.create("Scratch", root.resolve("scratch").toString(),
                true, true, true, 10_000L, 20_000L, 90, 30_000L, Set.of("npm"));

        assertEquals(second.id(), registry.active().id());
        assertFalse(registry.find("default").active());
        assertTrue(registry.find(second.id()).writeEnabled());
        assertEquals(Set.of("npm"), registry.find(second.id()).allowedCommands());
    }

    @Test
    void disablingActiveWorkspaceSelectsAnotherEnabledWorkspace() throws Exception {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        WorkspaceRegistry registry = new WorkspaceRegistry(store, root.toString(), 1000, 2000,
                false, 60, 3000, Set.of());
        WorkspaceProfile second = registry.create("Scratch", root.resolve("scratch").toString(),
                true, true, false, null, null, null, null, Set.of());

        registry.update(second.id(), new ObjectMapper().readTree("{\"enabled\":false}"));

        assertEquals("default", registry.active().id());
        assertFalse(registry.find(second.id()).active());
        assertFalse(registry.find(second.id()).enabled());
    }

    @Test
    void rejectsUnsafeAllowlistedCommands() {
        WorkspaceRegistry registry = new WorkspaceRegistry(new InMemoryWorkspaceStore(), root.toString(),
                1000, 2000, false, 60, 3000, Set.of());
        assertThrows(IllegalArgumentException.class, () -> registry.create("Unsafe", root.toString(),
                true, false, false, null, null, null, null, Set.of("sh -c")));
    }
}
