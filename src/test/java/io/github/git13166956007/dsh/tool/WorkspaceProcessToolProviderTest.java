package io.github.git13166956007.dsh.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WorkspaceProcessToolProviderTest {
    @TempDir
    Path root;

    @Test
    void runsOnlyAllowlistedCommandsAfterApproval() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry();
        try (WorkspaceProcessToolProvider provider = new WorkspaceProcessToolProvider(root, Set.of("printf"), 10,
                1024, mapper)) {
            provider.register(registry);
            JsonNode arguments = mapper.readTree("{\"command\":\"printf\",\"arguments\":[\"hello\"]}");
            assertThrows(ToolApprovalRequiredException.class, () -> registry.execute("workspace_exec", arguments));
            JsonNode result = mapper.readTree(registry.executeApproved("workspace_exec", arguments, null));
            assertEquals(0, result.path("exitCode").asInt());
            assertEquals("hello", result.path("output").asText());
        }
    }

    @Test
    void rejectsCommandsOutsideTheAllowlist() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry();
        try (WorkspaceProcessToolProvider provider = new WorkspaceProcessToolProvider(root, Set.of("printf"), 10,
                1024, mapper)) {
            provider.register(registry);
            assertThrows(IllegalArgumentException.class, () -> registry.executeApproved("workspace_exec",
                    mapper.readTree("{\"command\":\"sh\"}"), null));
            assertTrue(registry.list().stream().anyMatch(tool -> tool.name().equals("workspace_exec")));
        }
    }

    @Test
    void reportsTimeoutAndDoesNotLeaveTheProcessRunning() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry();
        try (WorkspaceProcessToolProvider provider = new WorkspaceProcessToolProvider(root, Set.of("sleep"), 1,
                1024, mapper)) {
            provider.register(registry);
            JsonNode result = mapper.readTree(registry.executeApproved("workspace_exec",
                    mapper.readTree("{\"command\":\"sleep\",\"arguments\":[\"5\"]}"), null));
            assertEquals(true, result.path("timedOut").asBoolean());
            assertEquals(-1, result.path("exitCode").asInt());
        }
    }
}
