package io.github.git13166956007.dsh.model;

import java.time.Instant;

public interface ModelHealthStore {
    ModelHealthData find(String modelId) throws Exception;

    void save(ModelHealthData health) throws Exception;

    void delete(String modelId) throws Exception;

    default void recordSuccess(String modelId, long latencyMs, Instant now) throws Exception {
        ModelHealthData previous = find(modelId);
        save(new ModelHealthData(modelId, "HEALTHY", previous == null ? 1 : previous.successCount() + 1,
                previous == null ? 0 : previous.failureCount(), Math.max(0, latencyMs), now, now, null));
    }

    default void recordFailure(String modelId, long latencyMs, String error, Instant now) throws Exception {
        ModelHealthData previous = find(modelId);
        save(new ModelHealthData(modelId, "UNHEALTHY", previous == null ? 0 : previous.successCount(),
                previous == null ? 1 : previous.failureCount() + 1, Math.max(0, latencyMs), now,
                previous == null ? null : previous.lastSuccessAt(), error));
    }
}
