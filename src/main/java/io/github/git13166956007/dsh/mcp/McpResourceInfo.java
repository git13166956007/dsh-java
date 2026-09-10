package io.github.git13166956007.dsh.mcp;

public record McpResourceInfo(
        String serverId,
        String uri,
        String name,
        String title,
        String description,
        String mimeType,
        Long size) {
}
