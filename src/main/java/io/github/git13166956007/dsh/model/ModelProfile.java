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
        boolean apiKeyConfigured) {
    public static ModelProfile from(ModelProfileData data) {
        return new ModelProfile(data.id(), data.name(), data.provider(), data.baseUrl(), data.model(),
                data.proxyHost(), data.proxyPort(), data.enabled(), data.active(),
                data.apiKey() != null && !data.apiKey().isBlank());
    }
}
