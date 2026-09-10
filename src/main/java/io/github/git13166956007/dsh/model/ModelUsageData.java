package io.github.git13166956007.dsh.model;

import java.time.Instant;

record ModelUsageData(
        String modelId,
        long requestCount,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        double estimatedCostUsd,
        Instant lastUsedAt) {
}
