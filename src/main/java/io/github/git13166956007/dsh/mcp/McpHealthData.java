package io.github.git13166956007.dsh.mcp;

import java.time.Instant;

record McpHealthData(
        String serverId,
        String status,
        long successCount,
        long failureCount,
        Long lastLatencyMs,
        Instant lastCheckedAt,
        Instant lastConnectedAt,
        Instant lastDisconnectedAt,
        String lastError) {
}
