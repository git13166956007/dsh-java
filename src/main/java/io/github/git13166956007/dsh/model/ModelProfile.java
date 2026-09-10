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
        int contextWindow) {
    public ModelProfile(String id, String name, String provider, String baseUrl, String model,
                        String proxyHost, int proxyPort, boolean enabled, boolean active, boolean apiKeyConfigured) {
        this(id, name, provider, baseUrl, model, proxyHost, proxyPort, enabled, active, apiKeyConfigured,
                true, true, false, 0);
    }

    public static ModelProfile from(ModelProfileData data) {
        return new ModelProfile(data.id(), data.name(), data.provider(), data.baseUrl(), data.model(),
                data.proxyHost(), data.proxyPort(), data.enabled(), data.active(),
                data.apiKey() != null && !data.apiKey().isBlank(), data.supportsTools(), data.supportsStreaming(),
                data.supportsVision(), data.contextWindow());
    }
}
