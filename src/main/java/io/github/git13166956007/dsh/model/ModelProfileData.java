package io.github.git13166956007.dsh.model;

public record ModelProfileData(
        String id,
        String name,
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        String proxyHost,
        int proxyPort,
        boolean enabled,
        boolean active,
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
    public ModelProfileData(String id, String name, String provider, String baseUrl, String model,
                            String apiKey, String proxyHost, int proxyPort, boolean enabled, boolean active,
                            boolean supportsTools, boolean supportsStreaming, boolean supportsVision,
                            int contextWindow, Double temperature, Double topP, Integer maxTokens,
                            Double frequencyPenalty, Double presencePenalty, int timeoutSeconds,
                            String requestOptionsJson) {
        this(id, name, provider, baseUrl, model, apiKey, proxyHost, proxyPort, enabled, active,
                supportsTools, supportsStreaming, supportsVision, contextWindow, temperature, topP, maxTokens,
                frequencyPenalty, presencePenalty, timeoutSeconds, requestOptionsJson, null, ModelFailoverPolicy.ANY_FAILURE.value());
    }

    public ModelProfileData(String id, String name, String provider, String baseUrl, String model,
                            String apiKey, String proxyHost, int proxyPort, boolean enabled, boolean active,
                            boolean supportsTools, boolean supportsStreaming, boolean supportsVision,
                            int contextWindow, Double temperature, Double topP, Integer maxTokens,
                            Double frequencyPenalty, Double presencePenalty, int timeoutSeconds,
                            String requestOptionsJson, String fallbackModelId) {
        this(id, name, provider, baseUrl, model, apiKey, proxyHost, proxyPort, enabled, active,
                supportsTools, supportsStreaming, supportsVision, contextWindow, temperature, topP, maxTokens,
                frequencyPenalty, presencePenalty, timeoutSeconds, requestOptionsJson, fallbackModelId,
                ModelFailoverPolicy.ANY_FAILURE.value());
    }

    public ModelProfileData(String id, String name, String provider, String baseUrl, String model,
                            String apiKey, String proxyHost, int proxyPort, boolean enabled, boolean active) {
        this(id, name, provider, baseUrl, model, apiKey, proxyHost, proxyPort, enabled, active,
                true, true, false, 0, null, null, null, null, null, 120, null, null,
                ModelFailoverPolicy.ANY_FAILURE.value());
    }
}
