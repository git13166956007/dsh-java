package io.github.git13166956007.dsh.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AgentProfileRegistry {
    private final AgentProfileStore store;
    private final int defaultMaxTurns;
    private final Map<String, AgentProfileData> profiles = new LinkedHashMap<String, AgentProfileData>();

    public AgentProfileRegistry(AgentProfileStore store, int defaultMaxTurns) {
        if (defaultMaxTurns < 1) throw new IllegalArgumentException("defaultMaxTurns must be positive");
        this.store = store;
        this.defaultMaxTurns = defaultMaxTurns;
        try {
            profiles.putAll(index(store.list()));
            if (profiles.isEmpty()) {
                AgentProfileData fallback = new AgentProfileData("default", "Default agent", AgentMode.CHAT,
                        null, "", defaultMaxTurns, true, true);
                profiles.put(fallback.id(), fallback);
                store.save(fallback);
            } else if (profiles.values().stream().noneMatch(AgentProfileData::active)) {
                activate(profiles.values().iterator().next().id());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load agent profiles", exception);
        }
    }

    public synchronized List<AgentProfile> list() {
        return profiles.values().stream().map(AgentProfile::from).toList();
    }

    public synchronized AgentProfile find(String id) {
        AgentProfileData profile = profiles.get(id);
        return profile == null ? null : AgentProfile.from(profile);
    }

    public synchronized AgentProfileData resolve(String id) {
        if (id != null && !id.trim().isEmpty()) {
            AgentProfileData explicit = require(id.trim());
            if (!explicit.enabled()) throw new IllegalStateException("agent profile is disabled: " + id);
            return explicit;
        }
        return profiles.values().stream().filter(profile -> profile.active() && profile.enabled()).findFirst()
                .orElseThrow(() -> new IllegalStateException("no enabled agent profile is active"));
    }

    public synchronized AgentProfile create(String name, AgentMode mode, String modelId, String systemPrompt,
                                             Integer maxTurns, Boolean enabled, Boolean active) {
        return create(name, mode, modelId, systemPrompt, maxTurns, enabled, active, 64, 300, 4);
    }

    public synchronized AgentProfile create(String name, AgentMode mode, String modelId, String systemPrompt,
                                             Integer maxTurns, Boolean enabled, Boolean active, Integer maxToolCalls,
                                             Integer timeoutSeconds, Integer maxDepth) {
        return create(name, mode, modelId, systemPrompt, maxTurns, enabled, active, maxToolCalls,
                timeoutSeconds, maxDepth, List.of(), List.of(), Map.of());
    }

    public synchronized AgentProfile create(String name, AgentMode mode, String modelId, String systemPrompt,
                                             Integer maxTurns, Boolean enabled, Boolean active, Integer maxToolCalls,
                                             Integer timeoutSeconds, Integer maxDepth, List<String> allowedToolNames,
                                             List<String> skillIds, Map<String, String> permissions) {
        String id = UUID.randomUUID().toString();
        boolean nextEnabled = enabled == null || enabled;
        boolean nextActive = nextEnabled && (Boolean.TRUE.equals(active)
                || profiles.values().stream().noneMatch(AgentProfileData::active));
        if (nextActive) deactivateAll();
        AgentProfileData profile = new AgentProfileData(id, required(name, "name"), mode == null ? AgentMode.CHAT : mode,
                blankToNull(modelId), blankToEmpty(systemPrompt), validMaxTurns(maxTurns == null ? defaultMaxTurns : maxTurns),
                validMaxToolCalls(maxToolCalls == null ? 64 : maxToolCalls),
                validTimeoutSeconds(timeoutSeconds == null ? 300 : timeoutSeconds),
                validMaxDepth(maxDepth == null ? 4 : maxDepth), nextEnabled, nextActive,
                normalizeList(allowedToolNames, "tool"), normalizeList(skillIds, "skill"), normalizePermissions(permissions));
        save(profile);
        return AgentProfile.from(profile);
    }

    public synchronized AgentProfile update(String id, String name, AgentMode mode, String modelId,
                                             String systemPrompt, Integer maxTurns, Boolean enabled, Boolean active) {
        return update(id, name, mode, modelId, systemPrompt, maxTurns, enabled, active, null, null, null);
    }

    public synchronized AgentProfile update(String id, String name, AgentMode mode, String modelId,
                                             String systemPrompt, Integer maxTurns, Boolean enabled, Boolean active,
                                             Integer maxToolCalls, Integer timeoutSeconds, Integer maxDepth) {
        return update(id, name, mode, modelId, systemPrompt, maxTurns, enabled, active, maxToolCalls,
                timeoutSeconds, maxDepth, null, null, null);
    }

    public synchronized AgentProfile update(String id, String name, AgentMode mode, String modelId,
                                             String systemPrompt, Integer maxTurns, Boolean enabled, Boolean active,
                                             Integer maxToolCalls, Integer timeoutSeconds, Integer maxDepth,
                                             List<String> allowedToolNames, List<String> skillIds,
                                             Map<String, String> permissions) {
        AgentProfileData current = require(id);
        boolean nextActive = active == null ? current.active() : active;
        boolean nextEnabled = enabled == null ? current.enabled() : enabled;
        if (!nextEnabled) nextActive = false;
        if (nextActive) deactivateAll();
        AgentProfileData updated = new AgentProfileData(id,
                name == null ? current.name() : required(name, "name"),
                mode == null ? current.mode() : mode,
                modelId == null ? current.modelId() : blankToNull(modelId),
                systemPrompt == null ? current.systemPrompt() : blankToEmpty(systemPrompt),
                maxTurns == null ? current.maxTurns() : validMaxTurns(maxTurns),
                maxToolCalls == null ? current.maxToolCalls() : validMaxToolCalls(maxToolCalls),
                timeoutSeconds == null ? current.timeoutSeconds() : validTimeoutSeconds(timeoutSeconds),
                maxDepth == null ? current.maxDepth() : validMaxDepth(maxDepth), nextEnabled, nextActive,
                allowedToolNames == null ? current.allowedToolNames() : normalizeList(allowedToolNames, "tool"),
                skillIds == null ? current.skillIds() : normalizeList(skillIds, "skill"),
                permissions == null ? current.permissions() : normalizePermissions(permissions));
        save(updated);
        ensureActive();
        return AgentProfile.from(profiles.get(id));
    }

    public synchronized AgentProfile activate(String id) {
        AgentProfileData target = require(id);
        if (!target.enabled()) throw new IllegalArgumentException("agent profile is disabled: " + id);
        deactivateAll();
        AgentProfileData active = new AgentProfileData(target.id(), target.name(), target.mode(), target.modelId(),
                target.systemPrompt(), target.maxTurns(), target.maxToolCalls(), target.timeoutSeconds(), target.maxDepth(), true, true,
                target.allowedToolNames(), target.skillIds(), target.permissions());
        save(active);
        return AgentProfile.from(active);
    }

    public synchronized boolean delete(String id) {
        AgentProfileData removed = profiles.remove(id);
        if (removed == null) return false;
        try {
            store.delete(id);
            ensureActive();
            return true;
        } catch (Exception exception) {
            profiles.put(id, removed);
            throw new IllegalStateException("failed to delete agent profile", exception);
        }
    }

    private void ensureActive() {
        if (profiles.values().stream().anyMatch(profile -> profile.active() && profile.enabled())) return;
        profiles.values().stream().filter(AgentProfileData::enabled).findFirst().ifPresent(profile -> activate(profile.id()));
    }

    private void deactivateAll() {
        for (AgentProfileData profile : new ArrayList<AgentProfileData>(profiles.values())) {
            if (profile.active()) {
                save(new AgentProfileData(profile.id(), profile.name(), profile.mode(), profile.modelId(),
                        profile.systemPrompt(), profile.maxTurns(), profile.maxToolCalls(), profile.timeoutSeconds(),
                        profile.maxDepth(), profile.enabled(), false, profile.allowedToolNames(), profile.skillIds(),
                        profile.permissions()));
            }
        }
    }

    private void save(AgentProfileData profile) {
        try {
            store.save(profile);
            profiles.put(profile.id(), profile);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to save agent profile", exception);
        }
    }

    private AgentProfileData require(String id) {
        AgentProfileData profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("unknown agent profile: " + id);
        return profile;
    }

    private static Map<String, AgentProfileData> index(List<AgentProfileData> values) {
        Map<String, AgentProfileData> result = new LinkedHashMap<String, AgentProfileData>();
        for (AgentProfileData profile : values) result.put(profile.id(), profile);
        return result;
    }

    private static int validMaxTurns(int value) {
        if (value < 1 || value > 64) throw new IllegalArgumentException("maxTurns must be between 1 and 64");
        return value;
    }

    private static int validMaxToolCalls(int value) {
        if (value < 0 || value > 10000) throw new IllegalArgumentException("maxToolCalls must be between 0 and 10000");
        return value;
    }

    private static int validTimeoutSeconds(int value) {
        if (value < 0 || value > 86400) throw new IllegalArgumentException("timeoutSeconds must be between 0 and 86400");
        return value;
    }

    private static int validMaxDepth(int value) {
        if (value < 0 || value > 32) throw new IllegalArgumentException("maxDepth must be between 0 and 32");
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

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> normalizeList(List<String> values, String kind) {
        if (values == null) return List.of();
        List<String> result = new ArrayList<String>();
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized == null) throw new IllegalArgumentException(kind + " name must not be blank");
            if (!result.contains(normalized)) result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static Map<String, String> normalizePermissions(Map<String, String> values) {
        if (values == null) return Map.of();
        Map<String, String> result = new java.util.LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = blankToNull(entry.getKey());
            if (key == null) throw new IllegalArgumentException("permission name must not be blank");
            result.put(key, entry.getValue() == null ? "" : entry.getValue().trim());
        }
        return Map.copyOf(result);
    }
}
