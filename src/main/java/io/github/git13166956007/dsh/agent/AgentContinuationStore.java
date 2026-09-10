package io.github.git13166956007.dsh.agent;

import java.util.List;

public interface AgentContinuationStore {
    String load(String runId) throws Exception;

    default List<String> listRunIds() throws Exception {
        return List.of();
    }

    void save(String runId, String payload) throws Exception;

    void delete(String runId) throws Exception;
}
