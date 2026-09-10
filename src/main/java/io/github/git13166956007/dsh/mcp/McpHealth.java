package io.github.git13166956007.dsh.mcp;

import java.time.Instant;

public record McpHealth(
        String serverId,
        String status,
        long successCount,
        long failureCount,
        Long lastLatencyMs,
        Instant lastCheckedAt,
        Instant lastConnectedAt,
        Instant lastDisconnectedAt,
        String lastError) {
    public static McpHealth unknown(String serverId) {
        return new McpHealth(serverId, "UNKNOWN", 0, 0, null, null, null, null, null);
    }

    static McpHealth from(McpHealthData data) {
        return new McpHealth(data.serverId(), data.status(), data.successCount(), data.failureCount(),
                data.lastLatencyMs(), data.lastCheckedAt(), data.lastConnectedAt(), data.lastDisconnectedAt(),
                data.lastError());
    }
}
