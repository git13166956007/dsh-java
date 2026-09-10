package io.github.git13166956007.dsh.model;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ModelRegistry {
    private final ModelProfileStore store;
    private final ModelHealthStore healthStore;
    private final Map<String, ModelProfileData> profiles = new LinkedHashMap<String, ModelProfileData>();
    private final Map<String, ModelProvider> providers = new ConcurrentHashMap<String, ModelProvider>();

    public ModelRegistry(ModelProfileStore store, String baseUrl, String provider, String model,
                         String apiKey, String proxyHost, int proxyPort) {
        this(store, new InMemoryModelHealthStore(), baseUrl, provider, model, apiKey, proxyHost, proxyPort);
    }

    public ModelRegistry(ModelProfileStore store, ModelHealthStore healthStore, String baseUrl, String provider,
                         String model, String apiKey, String proxyHost, int proxyPort) {
        this.store = store;
        this.healthStore = healthStore;
        try {
            profiles.putAll(index(store.list()));
            if (profiles.isEmpty()) {
                ModelProfileData fallback = new ModelProfileData("default", "Default model",
                        normalizeProvider(provider), normalizeUrl(baseUrl), required(model, "model"),
                        blankToNull(apiKey), blankToNull(proxyHost), validProxyPort(proxyPort), true, true,
                        true, true, false, 0, null, null, null, null, null, 120, null, null);
                profiles.put(fallback.id(), fallback);
                store.save(fallback);
            } else if (profiles.values().stream().noneMatch(ModelProfileData::active)) {
                ModelProfileData first = profiles.values().iterator().next();
                activate(first.id());
            }
            validateFallbackConfiguration();
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

    /** Registers a provider implementation supplied by the host or a plugin. */
    public void registerProvider(ModelProvider provider) {
        if (provider == null) throw new IllegalArgumentException("model provider must not be null");
        String id = required(provider.id(), "model provider id").toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid model provider id");
        ModelProvider previous = providers.putIfAbsent(id, provider);
        if (previous != null && previous != provider) {
            throw new IllegalArgumentException("duplicate model provider: " + id);
        }
    }

    public ModelProvider provider(String id) {
        String normalized = blankToNull(id);
        return normalized == null ? null : providers.get(normalized.toLowerCase(Locale.ROOT));
    }

    public List<String> providerIds() {
        return providers.keySet().stream().sorted().toList();
    }

    public synchronized ModelTokenizer tokenizer(String id) {
        ModelProfileData profile = resolve(id);
        ModelProvider provider = provider(profile.provider());
        if (provider == null) return ModelTokenizer.approximate();
        ModelTokenizer tokenizer = provider.tokenizer(profile);
        return tokenizer == null ? ModelTokenizer.approximate() : tokenizer;
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
        return create(name, provider, baseUrl, model, apiKey, proxyHost, proxyPort, enabled, active,
                supportsTools, supportsStreaming, supportsVision, contextWindow, temperature, topP, maxTokens,
                frequencyPenalty, presencePenalty, timeoutSeconds, requestOptionsJson, null,
                ModelFailoverPolicy.ANY_FAILURE.value());
    }

    public synchronized ModelProfile create(String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active, Boolean supportsTools,
                                             Boolean supportsStreaming, Boolean supportsVision, Integer contextWindow,
                                             Double temperature, Double topP, Integer maxTokens,
                                             Double frequencyPenalty, Double presencePenalty, Integer timeoutSeconds,
                                             String requestOptionsJson, String fallbackModelId) {
        return create(name, provider, baseUrl, model, apiKey, proxyHost, proxyPort, enabled, active,
                supportsTools, supportsStreaming, supportsVision, contextWindow, temperature, topP, maxTokens,
                frequencyPenalty, presencePenalty, timeoutSeconds, requestOptionsJson, fallbackModelId,
                ModelFailoverPolicy.ANY_FAILURE.value());
    }

    public synchronized ModelProfile create(String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active, Boolean supportsTools,
                                             Boolean supportsStreaming, Boolean supportsVision, Integer contextWindow,
                                             Double temperature, Double topP, Integer maxTokens,
                                             Double frequencyPenalty, Double presencePenalty, Integer timeoutSeconds,
                                             String requestOptionsJson, String fallbackModelId, String failoverPolicy) {
        String id = UUID.randomUUID().toString();
        boolean nextEnabled = enabled == null || enabled;
        boolean nextActive = nextEnabled && (Boolean.TRUE.equals(active)
                || profiles.values().stream().noneMatch(ModelProfileData::active));
        ModelProfileData profile = new ModelProfileData(id, required(name, "name"), normalizeProvider(provider),
                normalizeUrl(baseUrl), required(model, "model"), blankToNull(apiKey), blankToNull(proxyHost),
                validProxyPort(proxyPort == null ? 0 : proxyPort), nextEnabled, nextActive,
                supportsTools == null || supportsTools, supportsStreaming == null || supportsStreaming,
                Boolean.TRUE.equals(supportsVision), validContextWindow(contextWindow == null ? 0 : contextWindow),
                validTemperature(temperature), validTopP(topP), validMaxTokens(maxTokens),
                validPenalty(frequencyPenalty, "frequencyPenalty"), validPenalty(presencePenalty, "presencePenalty"),
                validTimeoutSeconds(timeoutSeconds == null ? 120 : timeoutSeconds),
                normalizeRequestOptions(requestOptionsJson), normalizeFallbackId(fallbackModelId),
                normalizeFailoverPolicy(failoverPolicy));
        validateFallback(profile.id(), profile.fallbackModelId());
        if (nextActive) deactivateAll();
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
        return update(id, name, provider, baseUrl, model, apiKey, proxyHost, proxyPort, enabled, active,
                supportsTools, supportsStreaming, supportsVision, contextWindow, temperature, topP, maxTokens,
                frequencyPenalty, presencePenalty, timeoutSeconds, requestOptionsJson, null);
    }

    public synchronized ModelProfile update(String id, String name, String provider, String baseUrl, String model,
                                             String apiKey, String proxyHost, Integer proxyPort,
                                             Boolean enabled, Boolean active, Boolean supportsTools,
                                             Boolean supportsStreaming, Boolean supportsVision, Integer contextWindow,
                                             Double temperature, Double topP, Integer maxTokens,
                                             Double frequencyPenalty, Double presencePenalty, Integer timeoutSeconds,
                                             String requestOptionsJson, String failoverPolicy) {
        ModelProfileData current = require(id);
        boolean nextActive = active == null ? current.active() : active;
        boolean nextEnabled = enabled == null ? current.enabled() : enabled;
        if (!nextEnabled) nextActive = false;
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
                requestOptionsJson == null ? current.requestOptionsJson() : normalizeRequestOptions(requestOptionsJson),
                current.fallbackModelId(), failoverPolicy == null ? current.failoverPolicy()
                        : normalizeFailoverPolicy(failoverPolicy));
        if (nextActive) deactivateAll();
        save(updated);
        ensureActive();
        return ModelProfile.from(profiles.get(id));
    }

    public synchronized ModelProfile update(String id, JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            throw new IllegalArgumentException("model patch must be a JSON object");
        }
        ModelProfileData current = require(id);
        boolean nextEnabled = booleanValue(patch, "enabled", current.enabled());
        boolean nextActive = booleanValue(patch, "active", current.active());
        if (!nextEnabled) nextActive = false;

        ModelProfileData updated = new ModelProfileData(id,
                requiredText(patch, "name", current.name()),
                normalizeProvider(textValue(patch, "provider", current.provider())),
                normalizeUrl(requiredText(patch, "baseUrl", current.baseUrl())),
                requiredText(patch, "model", current.model()),
                nullableText(patch, "apiKey", current.apiKey()),
                nullableText(patch, "proxyHost", current.proxyHost()),
                intValue(patch, "proxyPort", current.proxyPort(), 0, 65535, "proxyPort"),
                nextEnabled, nextActive,
                booleanValue(patch, "supportsTools", current.supportsTools()),
                booleanValue(patch, "supportsStreaming", current.supportsStreaming()),
                booleanValue(patch, "supportsVision", current.supportsVision()),
                intValue(patch, "contextWindow", current.contextWindow(), 0, 2_000_000, "contextWindow"),
                doubleValue(patch, "temperature", current.temperature(), 0, 2, "temperature"),
                doubleValue(patch, "topP", current.topP(), 0, 1, "topP"),
                integerValue(patch, "maxTokens", current.maxTokens(), 1, 2_000_000, "maxTokens"),
                doubleValue(patch, "frequencyPenalty", current.frequencyPenalty(), -2, 2, "frequencyPenalty"),
                doubleValue(patch, "presencePenalty", current.presencePenalty(), -2, 2, "presencePenalty"),
                intValue(patch, "timeoutSeconds", current.timeoutSeconds(), 1, 3600, "timeoutSeconds"),
                patch.has("requestOptionsJson") && !patch.path("requestOptionsJson").isNull()
                        ? normalizeRequestOptions(patch.path("requestOptionsJson").asText())
                        : patch.has("requestOptionsJson") ? null : current.requestOptionsJson(),
                patch.has("fallbackModelId") ? nullableText(patch, "fallbackModelId", current.fallbackModelId())
                        : current.fallbackModelId(),
                patch.has("failoverPolicy") && !patch.path("failoverPolicy").isNull()
                        ? normalizeFailoverPolicy(patch.path("failoverPolicy").asText())
                        : current.failoverPolicy());
        validateFallback(updated.id(), updated.fallbackModelId());
        if (nextActive) deactivateAll();
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
                target.presencePenalty(), target.timeoutSeconds(), target.requestOptionsJson(), target.fallbackModelId(),
                target.failoverPolicy());
        save(active);
        return ModelProfile.from(active);
    }

    public synchronized boolean delete(String id) {
        if (!profiles.containsKey(id)) return false;
        if (profiles.values().stream().anyMatch(profile -> id.equals(profile.fallbackModelId()))) {
            throw new IllegalArgumentException("model is referenced as a fallback: " + id);
        }
        ModelProfileData removed = profiles.remove(id);
        try {
            store.delete(id);
            healthStore.delete(id);
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

    public synchronized List<ModelProfileData> resolveCandidates(String id) {
        ModelProfileData primary = resolve(id);
        List<ModelProfileData> candidates = new ArrayList<ModelProfileData>();
        java.util.Set<String> visited = new java.util.HashSet<String>();
        ModelProfileData current = primary;
        while (current != null && visited.add(current.id())) {
            if (current.enabled()) candidates.add(current);
            String fallbackId = current.fallbackModelId();
            current = fallbackId == null ? null : profiles.get(fallbackId);
            if (fallbackId != null && current == null) {
                throw new IllegalStateException("unknown fallback model: " + fallbackId);
            }
        }
        if (current != null) throw new IllegalStateException("fallback model cycle detected");
        return List.copyOf(candidates);
    }

    public synchronized ModelHealth health(String id) {
        require(id);
        try {
            ModelHealthData value = healthStore.find(id);
            return value == null ? ModelHealth.unknown(id) : ModelHealth.from(value);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load model health", exception);
        }
    }

    public synchronized void recordSuccess(String id, long latencyMs) {
        recordHealth(id, true, latencyMs, null);
    }

    public synchronized void recordFailure(String id, long latencyMs, Throwable failure) {
        String message = failure == null ? "model request failed" : failure.getMessage();
        recordHealth(id, false, latencyMs, message == null ? failure.getClass().getSimpleName() : message);
    }

    private void recordHealth(String id, boolean success, long latencyMs, String error) {
        try {
            ModelHealthData previous = healthStore.find(id);
            long successes = previous == null ? 0 : previous.successCount();
            long failures = previous == null ? 0 : previous.failureCount();
            Instant now = Instant.now();
            ModelHealthData next = new ModelHealthData(id, success ? "HEALTHY" : "UNHEALTHY",
                    success ? successes + 1 : successes, success ? failures : failures + 1,
                    Math.max(0, latencyMs), now, success ? now : previous == null ? null : previous.lastSuccessAt(),
                    success ? null : error);
            healthStore.save(next);
        } catch (Exception exception) {
            // Health telemetry must not turn a successful model request into a failed request.
        }
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
                        profile.presencePenalty(), profile.timeoutSeconds(), profile.requestOptionsJson(), profile.fallbackModelId(),
                        profile.failoverPolicy()));
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

    private void validateFallbackConfiguration() {
        for (ModelProfileData profile : profiles.values()) {
            validateFallback(profile.id(), profile.fallbackModelId());
        }
    }

    private void validateFallback(String id, String fallbackId) {
        String normalized = normalizeFallbackId(fallbackId);
        if (normalized == null) return;
        if (id.equals(normalized)) throw new IllegalArgumentException("model cannot fall back to itself");
        require(normalized);
        java.util.Set<String> visited = new java.util.HashSet<String>();
        String current = id;
        while (current != null && visited.add(current)) {
            String next = current.equals(id) ? normalized : require(current).fallbackModelId();
            if (next == null) return;
            if (id.equals(next)) throw new IllegalArgumentException("fallback model cycle detected");
            current = next;
        }
        throw new IllegalArgumentException("fallback model cycle detected");
    }

    private static String normalizeFallbackId(String value) {
        return blankToNull(value);
    }

    private static String normalizeFailoverPolicy(String value) {
        return ModelFailoverPolicy.parse(value).value();
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

    private static String textValue(JsonNode patch, String field, String fallback) {
        return patch.has(field) && !patch.path(field).isNull() ? patch.path(field).asText() : fallback;
    }

    private static String requiredText(JsonNode patch, String field, String fallback) {
        return required(textValue(patch, field, fallback), field);
    }

    private static String nullableText(JsonNode patch, String field, String fallback) {
        return patch.has(field) && !patch.path(field).isNull() ? blankToNull(patch.path(field).asText())
                : patch.has(field) ? null : fallback;
    }

    private static boolean booleanValue(JsonNode patch, String field, boolean fallback) {
        JsonNode value = patch.path(field);
        if (!patch.has(field) || value.isNull()) return fallback;
        if (!value.isBoolean()) throw new IllegalArgumentException(field + " must be boolean");
        return value.asBoolean();
    }

    private static int intValue(JsonNode patch, String field, int fallback, int minimum, int maximum, String label) {
        JsonNode value = patch.path(field);
        if (!patch.has(field) || value.isNull()) return fallback;
        if (!value.isIntegralNumber()) throw new IllegalArgumentException(label + " must be an integer");
        int number = value.asInt();
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum);
        }
        return number;
    }

    private static Integer integerValue(JsonNode patch, String field, Integer fallback,
                                        int minimum, int maximum, String label) {
        JsonNode value = patch.path(field);
        if (!patch.has(field)) return fallback;
        if (value.isNull()) return null;
        if (!value.isIntegralNumber()) throw new IllegalArgumentException(label + " must be an integer");
        int number = value.asInt();
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum);
        }
        return number;
    }

    private static Double doubleValue(JsonNode patch, String field, Double fallback,
                                      double minimum, double maximum, String label) {
        JsonNode value = patch.path(field);
        if (!patch.has(field)) return fallback;
        if (value.isNull()) return null;
        if (!value.isNumber()) throw new IllegalArgumentException(label + " must be a number");
        double number = value.asDouble();
        if (Double.isNaN(number) || Double.isInfinite(number) || number < minimum || number > maximum
                || ("topP".equals(label) && number == 0)) {
            throw new IllegalArgumentException("topP".equals(label)
                    ? "topP must be greater than 0 and at most 1"
                    : label + " must be between " + minimum + " and " + maximum);
        }
        return number;
    }

    private static String normalizeRequestOptions(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) return null;
        try {
            JsonNode node = new ObjectMapper().readTree(normalized);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("requestOptionsJson must be a JSON object");
            }
            for (String reserved : List.of("model", "messages", "stream", "tools", "tool_choice",
                    "temperature", "top_p", "max_tokens", "frequency_penalty", "presence_penalty")) {
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
