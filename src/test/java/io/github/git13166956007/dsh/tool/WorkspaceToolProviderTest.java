package io.github.git13166956007.dsh.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class WorkspaceToolProviderTest {
    @TempDir
    Path root;

    @Test
    void readsListsAndWritesOnlyInsideTheWorkspace() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/readme.txt"), "hello");
        ToolRegistry registry = new ToolRegistry();
        new WorkspaceToolProvider(root, 1024, 1024, true, new ObjectMapper()).register(registry);

        assertTrue(registry.definitions().stream().anyMatch(tool -> tool.name().equals("workspace_read_file")));
        assertEquals("hello", registry.execute("workspace_read_file", new ObjectMapper().readTree(
                "{\"path\":\"docs/readme.txt\"}")));
        assertTrue(registry.execute("workspace_list_files", new ObjectMapper().readTree(
                "{\"path\":\"docs\"}" )).contains("readme.txt"));
        assertThrows(ToolApprovalRequiredException.class, () -> registry.execute("workspace_write_file",
                new ObjectMapper().readTree("{\"path\":\"docs/out.txt\",\"content\":\"written\"}")));
        registry.executeApproved("workspace_write_file", new ObjectMapper().readTree(
                "{\"path\":\"docs/out.txt\",\"content\":\"written\"}"), null);
        assertEquals("written", Files.readString(root.resolve("docs/out.txt")));
    }

    @Test
    void rejectsTraversalSymlinksAndOversizedFiles() throws Exception {
        Files.writeString(root.resolve("large.txt"), "123456");
        ToolRegistry registry = new ToolRegistry();
        new WorkspaceToolProvider(root, 5, 5, true, new ObjectMapper()).register(registry);

        assertThrows(IllegalArgumentException.class, () -> registry.execute("workspace_read_file",
                new ObjectMapper().readTree("{\"path\":\"../outside.txt\"}")));
        assertThrows(IllegalArgumentException.class, () -> registry.execute("workspace_read_file",
                new ObjectMapper().readTree("{\"path\":\"large.txt\"}")));
        assertThrows(IllegalArgumentException.class, () -> registry.executeApproved("workspace_write_file",
                new ObjectMapper().readTree("{\"path\":\"../outside.txt\",\"content\":\"x\"}"), null));
    }
}
