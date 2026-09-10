package io.github.git13166956007.dsh.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryMcpResourceSubscriptionStore implements McpResourceSubscriptionStore {
    private final Map<String, McpResourceSubscription> subscriptions = new LinkedHashMap<String, McpResourceSubscription>();

    @Override
    public synchronized List<McpResourceSubscription> list(String serverId) {
        return subscriptions.values().stream()
                .filter(value -> value.serverId().equals(serverId))
                .toList();
    }

    @Override
    public synchronized void save(McpResourceSubscription subscription) {
        subscriptions.put(key(subscription.serverId(), subscription.uri()), subscription);
    }

    @Override
    public synchronized void delete(String serverId, String uri) {
        subscriptions.remove(key(serverId, uri));
    }

    @Override
    public synchronized void deleteServer(String serverId) {
        new ArrayList<String>(subscriptions.keySet()).stream()
                .filter(key -> key.startsWith(serverId + "\u0000"))
                .forEach(subscriptions::remove);
    }

    private static String key(String serverId, String uri) {
        return serverId + "\u0000" + uri;
    }
}
