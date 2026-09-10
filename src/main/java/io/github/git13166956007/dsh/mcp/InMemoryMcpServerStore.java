package io.github.git13166956007.dsh.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryMcpServerStore implements McpServerStore {
    private final Map<String, McpServerInfo> servers = new LinkedHashMap<String, McpServerInfo>();

    @Override
    public synchronized List<McpServerInfo> list() {
        return new ArrayList<McpServerInfo>(servers.values());
    }

    @Override
    public synchronized void save(McpServerInfo server) {
        servers.put(server.id(), server);
    }

    @Override
    public synchronized void delete(String id) {
        servers.remove(id);
    }
}
