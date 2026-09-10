package io.github.git13166956007.dsh.model;

public record ModelProfile(
        String id,
        String name,
        String provider,
        String baseUrl,
        String model,
        String proxyHost,
        int proxyPort,
        boolean enabled,
        boolean active,
        boolean apiKeyConfigured,
        boolean supportsTools,
        boolean supportsStreaming,
        boolean supportsVision,
        int contextWindow,
        Double temperature,
        Double topP,
        Integer maxTokens,
        Double frequencyPenalty,
        Double presencePenalty,
        int timeoutSeconds,
        String requestOptionsJson,
        String fallbackModelId,
        String failoverPolicy) {
    public ModelProfile(String id, String name, String provider, String baseUrl, String model,
                        String proxyHost, int proxyPort, boolean enabled, boolean active, boolean apiKeyConfigured) {
        this(id, name, provider, baseUrl, model, proxyHost, proxyPort, enabled, active, apiKeyConfigured,
                true, true, false, 0, null, null, null, null, null, 120, null, null,
                ModelFailoverPolicy.ANY_FAILURE.value());
    }

    public static ModelProfile from(ModelProfileData data) {
        return new ModelProfile(data.id(), data.name(), data.provider(), data.baseUrl(), data.model(),
                data.proxyHost(), data.proxyPort(), data.enabled(), data.active(),
                data.apiKey() != null && !data.apiKey().isBlank(), data.supportsTools(), data.supportsStreaming(),
                data.supportsVision(), data.contextWindow(), data.temperature(), data.topP(), data.maxTokens(),
                data.frequencyPenalty(), data.presencePenalty(), data.timeoutSeconds(), data.requestOptionsJson(),
                data.fallbackModelId(), ModelFailoverPolicy.parse(data.failoverPolicy()).value());
    }
}
