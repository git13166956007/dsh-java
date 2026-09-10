package io.github.git13166956007.dsh.mcp;

import java.time.Instant;

public record McpResourceSubscription(String serverId, String uri, Instant subscribedAt) {
}
