package io.github.git13166956007.dsh.agent;

public interface AgentContinuationStore {
    String load(String runId) throws Exception;

    void save(String runId, String payload) throws Exception;

    void delete(String runId) throws Exception;
}
