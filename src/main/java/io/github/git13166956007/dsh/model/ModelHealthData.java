package io.github.git13166956007.dsh.model;

import java.time.Instant;

record ModelHealthData(
        String modelId,
        String status,
        long successCount,
        long failureCount,
        Long lastLatencyMs,
        Instant lastCheckedAt,
        Instant lastSuccessAt,
        String lastError) {
}
