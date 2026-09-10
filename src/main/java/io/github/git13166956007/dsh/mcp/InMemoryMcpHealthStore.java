package io.github.git13166956007.dsh.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

public final class InMemoryMcpHealthStore implements McpHealthStore {
    private final Map<String, McpHealthData> health = new LinkedHashMap<String, McpHealthData>();

    @Override
    public synchronized McpHealthData find(String serverId) {
        return health.get(serverId);
    }

    @Override
    public synchronized void save(McpHealthData value) {
        health.put(value.serverId(), value);
    }

    @Override
    public synchronized void delete(String serverId) {
        health.remove(serverId);
    }
}
