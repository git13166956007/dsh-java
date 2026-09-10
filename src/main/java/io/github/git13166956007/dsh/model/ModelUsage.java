package io.github.git13166956007.dsh.model;

import java.time.Instant;

public record ModelUsage(
        String modelId,
        long requestCount,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        double estimatedCostUsd,
        Instant lastUsedAt) {
    public static ModelUsage unknown(String modelId) {
        return new ModelUsage(modelId, 0, 0, 0, 0, 0, null);
    }

    static ModelUsage from(ModelUsageData data) {
        return new ModelUsage(data.modelId(), data.requestCount(), data.promptTokens(), data.completionTokens(),
                data.totalTokens(), data.estimatedCostUsd(), data.lastUsedAt());
    }
}
