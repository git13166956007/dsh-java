package io.github.git13166956007.dsh.mcp;

public interface McpHealthStore {
    McpHealthData find(String serverId) throws Exception;

    void save(McpHealthData health) throws Exception;

    void delete(String serverId) throws Exception;
}
