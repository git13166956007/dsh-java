package io.github.git13166956007.dsh.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryMcpServerStore implements McpServerStore {
    private final Map<String, McpServerInfo> servers = new LinkedHashMap<String, McpServerInfo>();
    private final Map<String, McpServerSecrets> secrets = new LinkedHashMap<String, McpServerSecrets>();

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
        secrets.remove(id);
    }

    @Override
    public synchronized McpServerSecrets loadSecrets(String id) {
        return secrets.getOrDefault(id, McpServerSecrets.empty());
    }

    @Override
    public synchronized void saveSecrets(String id, McpServerSecrets value) {
        secrets.put(id, value == null ? McpServerSecrets.empty() : value);
    }
}
