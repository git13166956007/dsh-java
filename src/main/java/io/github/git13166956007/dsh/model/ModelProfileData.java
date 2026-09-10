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
        boolean active) {
}
