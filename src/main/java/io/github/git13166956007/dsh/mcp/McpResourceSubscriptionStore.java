package io.github.git13166956007.dsh.mcp;

import java.util.List;

public interface McpResourceSubscriptionStore {
    List<McpResourceSubscription> list(String serverId) throws Exception;

    void save(McpResourceSubscription subscription) throws Exception;

    void delete(String serverId, String uri) throws Exception;

    void deleteServer(String serverId) throws Exception;
}
