package io.github.git13166956007.dsh.mcp;

import java.util.List;

public interface McpServerStore {
    List<McpServerInfo> list() throws Exception;

    void save(McpServerInfo server) throws Exception;

    void delete(String id) throws Exception;
}
