package io.github.git13166956007.dsh.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ModelRegistry {
    private final ModelProfileStore store;
    private final Map<String, ModelProfileData> profiles = new LinkedHashMap<String, ModelProfileData>();

    public ModelRegistry(ModelProfileStore store, String baseUrl, String provider, String model,
                         String apiKey, String proxyHost, int proxyPort) {
        this.store = store;
        try {
            profiles.putAll(index(store.list()));
            if (profiles.isEmpty()) {
                ModelProfileData fallback = new ModelProfileData("default", "Default model",
                        normalizeProvider(provider), normalizeUrl(baseUrl), required(model, "model"),
                        blankToNull(apiKey), blankToNull(proxyHost), validProxyPort(proxyPort), true, true);
                profiles.put(fallback.id(), fallback);
                store.save(fallback);
            } else if (profiles.values().stream().noneMatch(ModelProfileData::active)) {
                ModelProfileData first = profiles.values().iterator().next();
                activate(first.id());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load model profiles", exception);
        }
    }

    public synchronized List<ModelProfile> list() {
        return profiles.values().stream().map(ModelProfile::from).toList();
    }

    public synchronized ModelProfile find(String id) {
        ModelProfileData profile = profiles.get(id);
        return profile == null ? null : ModelProfile.from(profile);
    }

    public synchronized ModelProfile create(String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active) {
        return create(name, provider, baseUrl, model, apiKey, proxyHost, proxyPort, enabled, active,
                true, true, false, 0);
    }

    public synchronized ModelProfile create(String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active, Boolean supportsTools,
                                             Boolean supportsStreaming, Boolean supportsVision, Integer contextWindow) {
        String id = UUID.randomUUID().toString();
        boolean nextEnabled = enabled == null || enabled;
        boolean nextActive = nextEnabled && (Boolean.TRUE.equals(active)
                || profiles.values().stream().noneMatch(ModelProfileData::active));
        if (nextActive) deactivateAll();
        ModelProfileData profile = new ModelProfileData(id, required(name, "name"), normalizeProvider(provider),
                normalizeUrl(baseUrl), required(model, "model"), blankToNull(apiKey), blankToNull(proxyHost),
                validProxyPort(proxyPort == null ? 0 : proxyPort), nextEnabled, nextActive,
                supportsTools == null || supportsTools, supportsStreaming == null || supportsStreaming,
                Boolean.TRUE.equals(supportsVision), validContextWindow(contextWindow == null ? 0 : contextWindow));
        save(profile);
        return ModelProfile.from(profile);
    }

    public synchronized ModelProfile update(String id, String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active) {
        return update(id, name, provider, baseUrl, model, apiKey, proxyHost, proxyPort, enabled, active,
                null, null, null, null);
    }

    public synchronized ModelProfile update(String id, String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active, Boolean supportsTools,
                                             Boolean supportsStreaming, Boolean supportsVision, Integer contextWindow) {
        ModelProfileData current = require(id);
        boolean nextActive = active == null ? current.active() : active;
        boolean nextEnabled = enabled == null ? current.enabled() : enabled;
        if (!nextEnabled) nextActive = false;
        if (nextActive) deactivateAll();
        ModelProfileData updated = new ModelProfileData(id,
                name == null ? current.name() : required(name, "name"),
                provider == null ? current.provider() : normalizeProvider(provider),
                baseUrl == null ? current.baseUrl() : normalizeUrl(baseUrl),
                model == null ? current.model() : required(model, "model"),
                apiKey == null ? current.apiKey() : blankToNull(apiKey),
                proxyHost == null ? current.proxyHost() : blankToNull(proxyHost),
                validProxyPort(proxyPort == null ? current.proxyPort() : proxyPort), nextEnabled, nextActive,
                supportsTools == null ? current.supportsTools() : supportsTools,
                supportsStreaming == null ? current.supportsStreaming() : supportsStreaming,
                supportsVision == null ? current.supportsVision() : supportsVision,
                contextWindow == null ? current.contextWindow() : validContextWindow(contextWindow));
        save(updated);
        ensureActive();
        return ModelProfile.from(profiles.get(id));
    }

    public synchronized ModelProfile activate(String id) {
        ModelProfileData target = require(id);
        if (!target.enabled()) throw new IllegalArgumentException("model is disabled: " + id);
        deactivateAll();
        ModelProfileData active = new ModelProfileData(target.id(), target.name(), target.provider(), target.baseUrl(),
                target.model(), target.apiKey(), target.proxyHost(), target.proxyPort(), true, true,
                target.supportsTools(), target.supportsStreaming(), target.supportsVision(), target.contextWindow());
        save(active);
        return ModelProfile.from(active);
    }

    public synchronized boolean delete(String id) {
        ModelProfileData removed = profiles.remove(id);
        if (removed == null) return false;
        try {
            store.delete(id);
            ensureActive();
            return true;
        } catch (Exception exception) {
            profiles.put(id, removed);
            throw new IllegalStateException("failed to delete model profile", exception);
        }
    }

    public synchronized ModelProfileData resolve(String id) {
        if (id != null && !id.trim().isEmpty()) {
            ModelProfileData explicit = require(id.trim());
            if (!explicit.enabled()) throw new IllegalStateException("model is disabled: " + id);
            return explicit;
        }
        return profiles.values().stream().filter(profile -> profile.active() && profile.enabled()).findFirst()
                .orElseThrow(() -> new IllegalStateException("no enabled model profile is active"));
    }

    private void ensureActive() {
        if (profiles.values().stream().anyMatch(profile -> profile.active() && profile.enabled())) return;
        profiles.values().stream().filter(ModelProfileData::enabled).findFirst().ifPresent(profile -> {
            try { activate(profile.id()); } catch (Exception exception) { throw new IllegalStateException(exception); }
        });
    }

    private void deactivateAll() {
        for (ModelProfileData profile : new ArrayList<ModelProfileData>(profiles.values())) {
            if (profile.active()) {
                save(new ModelProfileData(profile.id(), profile.name(), profile.provider(), profile.baseUrl(),
                        profile.model(), profile.apiKey(), profile.proxyHost(), profile.proxyPort(), profile.enabled(), false,
                        profile.supportsTools(), profile.supportsStreaming(), profile.supportsVision(), profile.contextWindow()));
            }
        }
    }

    private void save(ModelProfileData profile) {
        try {
            store.save(profile);
            profiles.put(profile.id(), profile);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to save model profile", exception);
        }
    }

    private ModelProfileData require(String id) {
        ModelProfileData profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("unknown model: " + id);
        return profile;
    }

    private static Map<String, ModelProfileData> index(List<ModelProfileData> values) {
        Map<String, ModelProfileData> result = new LinkedHashMap<String, ModelProfileData>();
        for (ModelProfileData profile : values) result.put(profile.id(), profile);
        return result;
    }

    private static String normalizeProvider(String provider) {
        String value = required(provider, "provider").toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid provider");
        return value;
    }

    private static String normalizeUrl(String value) {
        String normalized = required(value, "baseUrl");
        URI uri = URI.create(normalized);
        if (!uri.isAbsolute() || uri.getHost() == null) throw new IllegalArgumentException("baseUrl must be an absolute URL");
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static int validProxyPort(int value) {
        if (value < 0 || value > 65535) throw new IllegalArgumentException("proxyPort must be between 0 and 65535");
        return value;
    }

    private static int validContextWindow(int value) {
        if (value < 0 || value > 2_000_000) throw new IllegalArgumentException("contextWindow must be between 0 and 2000000");
        return value;
    }

    private static String required(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
