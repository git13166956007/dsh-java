package io.github.git13166956007.dsh.model;

import java.time.Instant;

public record ModelHealth(
        String modelId,
        String status,
        long successCount,
        long failureCount,
        Long lastLatencyMs,
        Instant lastCheckedAt,
        Instant lastSuccessAt,
        String lastError) {
    public static ModelHealth unknown(String modelId) {
        return new ModelHealth(modelId, "UNKNOWN", 0, 0, null, null, null, null);
    }

    static ModelHealth from(ModelHealthData data) {
        return new ModelHealth(data.modelId(), data.status(), data.successCount(), data.failureCount(),
                data.lastLatencyMs(), data.lastCheckedAt(), data.lastSuccessAt(), data.lastError());
    }
}
