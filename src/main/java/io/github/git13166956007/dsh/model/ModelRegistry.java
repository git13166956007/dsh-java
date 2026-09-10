package io.github.git13166956007.dsh.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
                        blankToNull(apiKey), blankToNull(proxyHost), validProxyPort(proxyPort), true, true,
                        true, true, false, 0, null, null, null, null, null, 120, null);
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
                true, true, false, 0, null, null, null, null, null, 120, null);
    }

    public synchronized ModelProfile create(String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active, Boolean supportsTools,
                                             Boolean supportsStreaming, Boolean supportsVision, Integer contextWindow) {
        return create(name, provider, baseUrl, model, apiKey, proxyHost, proxyPort, enabled, active,
                supportsTools, supportsStreaming, supportsVision, contextWindow, null, null, null, null, null, 120, null);
    }

    public synchronized ModelProfile create(String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active, Boolean supportsTools,
                                             Boolean supportsStreaming, Boolean supportsVision, Integer contextWindow,
                                             Double temperature, Double topP, Integer maxTokens,
                                             Double frequencyPenalty, Double presencePenalty, Integer timeoutSeconds) {
        return create(name, provider, baseUrl, model, apiKey, proxyHost, proxyPort, enabled, active,
                supportsTools, supportsStreaming, supportsVision, contextWindow, temperature, topP, maxTokens,
                frequencyPenalty, presencePenalty, timeoutSeconds, null);
    }

    public synchronized ModelProfile create(String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active, Boolean supportsTools,
                                             Boolean supportsStreaming, Boolean supportsVision, Integer contextWindow,
                                             Double temperature, Double topP, Integer maxTokens,
                                             Double frequencyPenalty, Double presencePenalty, Integer timeoutSeconds,
                                             String requestOptionsJson) {
        String id = UUID.randomUUID().toString();
        boolean nextEnabled = enabled == null || enabled;
        boolean nextActive = nextEnabled && (Boolean.TRUE.equals(active)
                || profiles.values().stream().noneMatch(ModelProfileData::active));
        if (nextActive) deactivateAll();
        ModelProfileData profile = new ModelProfileData(id, required(name, "name"), normalizeProvider(provider),
                normalizeUrl(baseUrl), required(model, "model"), blankToNull(apiKey), blankToNull(proxyHost),
                validProxyPort(proxyPort == null ? 0 : proxyPort), nextEnabled, nextActive,
                supportsTools == null || supportsTools, supportsStreaming == null || supportsStreaming,
                Boolean.TRUE.equals(supportsVision), validContextWindow(contextWindow == null ? 0 : contextWindow),
                validTemperature(temperature), validTopP(topP), validMaxTokens(maxTokens),
                validPenalty(frequencyPenalty, "frequencyPenalty"), validPenalty(presencePenalty, "presencePenalty"),
                validTimeoutSeconds(timeoutSeconds == null ? 120 : timeoutSeconds),
                normalizeRequestOptions(requestOptionsJson));
        save(profile);
        return ModelProfile.from(profile);
    }

    public synchronized ModelProfile update(String id, String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active) {
        return update(id, name, provider, baseUrl, model, apiKey, proxyHost, proxyPort, enabled, active,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    public synchronized ModelProfile update(String id, String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active, Boolean supportsTools,
                                             Boolean supportsStreaming, Boolean supportsVision, Integer contextWindow) {
        return update(id, name, provider, baseUrl, model, apiKey, proxyHost, proxyPort, enabled, active,
                supportsTools, supportsStreaming, supportsVision, contextWindow, null, null, null, null, null, null, null);
    }

    public synchronized ModelProfile update(String id, String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active, Boolean supportsTools,
                                             Boolean supportsStreaming, Boolean supportsVision, Integer contextWindow,
                                             Double temperature, Double topP, Integer maxTokens,
                                             Double frequencyPenalty, Double presencePenalty, Integer timeoutSeconds) {
        return update(id, name, provider, baseUrl, model, apiKey, proxyHost, proxyPort, enabled, active,
                supportsTools, supportsStreaming, supportsVision, contextWindow, temperature, topP, maxTokens,
                frequencyPenalty, presencePenalty, timeoutSeconds, null);
    }

    public synchronized ModelProfile update(String id, String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active, Boolean supportsTools,
                                             Boolean supportsStreaming, Boolean supportsVision, Integer contextWindow,
                                             Double temperature, Double topP, Integer maxTokens,
                                             Double frequencyPenalty, Double presencePenalty, Integer timeoutSeconds,
                                             String requestOptionsJson) {
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
                contextWindow == null ? current.contextWindow() : validContextWindow(contextWindow),
                temperature == null ? current.temperature() : validTemperature(temperature),
                topP == null ? current.topP() : validTopP(topP),
                maxTokens == null ? current.maxTokens() : validMaxTokens(maxTokens),
                frequencyPenalty == null ? current.frequencyPenalty() : validPenalty(frequencyPenalty, "frequencyPenalty"),
                presencePenalty == null ? current.presencePenalty() : validPenalty(presencePenalty, "presencePenalty"),
                timeoutSeconds == null ? current.timeoutSeconds() : validTimeoutSeconds(timeoutSeconds),
                requestOptionsJson == null ? current.requestOptionsJson() : normalizeRequestOptions(requestOptionsJson));
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
                target.supportsTools(), target.supportsStreaming(), target.supportsVision(), target.contextWindow(),
                target.temperature(), target.topP(), target.maxTokens(), target.frequencyPenalty(),
                target.presencePenalty(), target.timeoutSeconds(), target.requestOptionsJson());
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
                        profile.supportsTools(), profile.supportsStreaming(), profile.supportsVision(), profile.contextWindow(),
                        profile.temperature(), profile.topP(), profile.maxTokens(), profile.frequencyPenalty(),
                        profile.presencePenalty(), profile.timeoutSeconds(), profile.requestOptionsJson()));
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

    private static Double validTemperature(Double value) {
        if (value == null) return null;
        if (value.isNaN() || value.isInfinite() || value < 0 || value > 2) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        return value;
    }

    private static Double validTopP(Double value) {
        if (value == null) return null;
        if (value.isNaN() || value.isInfinite() || value <= 0 || value > 1) {
            throw new IllegalArgumentException("topP must be greater than 0 and at most 1");
        }
        return value;
    }

    private static Integer validMaxTokens(Integer value) {
        if (value == null) return null;
        if (value < 1 || value > 2_000_000) throw new IllegalArgumentException("maxTokens must be between 1 and 2000000");
        return value;
    }

    private static Double validPenalty(Double value, String field) {
        if (value == null) return null;
        if (value.isNaN() || value.isInfinite() || value < -2 || value > 2) {
            throw new IllegalArgumentException(field + " must be between -2 and 2");
        }
        return value;
    }

    private static int validTimeoutSeconds(int value) {
        if (value < 1 || value > 3600) throw new IllegalArgumentException("timeoutSeconds must be between 1 and 3600");
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

    private static String normalizeRequestOptions(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) return null;
        try {
            JsonNode node = new ObjectMapper().readTree(normalized);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("requestOptionsJson must be a JSON object");
            }
            for (String reserved : List.of("model", "messages", "stream", "tools", "tool_choice")) {
                if (node.has(reserved)) {
                    throw new IllegalArgumentException("requestOptionsJson must not define " + reserved);
                }
            }
            return node.toString();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("requestOptionsJson must be valid JSON", exception);
        }
    }
}
