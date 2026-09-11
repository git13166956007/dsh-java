package io.github.git13166956007.dsh.mcp;

import java.time.Instant;

public interface McpHealthStore {
    McpHealthData find(String serverId) throws Exception;

    void save(McpHealthData health) throws Exception;

    void delete(String serverId) throws Exception;

    default void recordSuccess(String serverId, long latencyMs, Instant now) throws Exception {
        McpHealthData previous = find(serverId);
        save(new McpHealthData(serverId, "HEALTHY", previous == null ? 1 : previous.successCount() + 1,
                previous == null ? 0 : previous.failureCount(), Math.max(0, latencyMs), now, now,
                previous == null ? null : previous.lastDisconnectedAt(), null));
    }

    default void recordFailure(String serverId, long latencyMs, String error, Instant now) throws Exception {
        McpHealthData previous = find(serverId);
        save(new McpHealthData(serverId, "UNHEALTHY", previous == null ? 0 : previous.successCount(),
                previous == null ? 1 : previous.failureCount() + 1, Math.max(0, latencyMs), now,
                previous == null ? null : previous.lastConnectedAt(), previous == null ? null : previous.lastDisconnectedAt(),
                error));
    }
}
