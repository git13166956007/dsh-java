package io.github.git13166956007.dsh.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryAgentContinuationStore implements AgentContinuationStore {
    private final Map<String, String> values = new ConcurrentHashMap<String, String>();

    @Override
    public String load(String runId) {
        return values.get(runId);
    }

    @Override
    public void save(String runId, String payload) {
        values.put(runId, payload);
    }

    @Override
    public void delete(String runId) {
        values.remove(runId);
    }
}
