package io.github.git13166956007.dsh.mcp;

import java.time.Instant;
import java.util.List;

public record McpResourceUpdate(String serverId, String uri, List<McpResourceContent> contents,
                                Instant updatedAt) {
    public McpResourceUpdate {
        contents = List.copyOf(contents == null ? List.of() : contents);
    }
}
