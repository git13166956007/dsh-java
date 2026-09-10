package io.github.git13166956007.dsh.mcp;

import java.util.List;

public interface McpServerStore {
    List<McpServerInfo> list() throws Exception;

    void save(McpServerInfo server) throws Exception;

    void delete(String id) throws Exception;

    default McpServerSecrets loadSecrets(String id) throws Exception {
        return McpServerSecrets.empty();
    }

    default void saveSecrets(String id, McpServerSecrets secrets) throws Exception {
    }
}
