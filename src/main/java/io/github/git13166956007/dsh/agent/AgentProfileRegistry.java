package io.github.git13166956007.dsh.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        String id = UUID.randomUUID().toString();
        boolean nextEnabled = enabled == null || enabled;
        boolean nextActive = nextEnabled && (Boolean.TRUE.equals(active)
                || profiles.values().stream().noneMatch(AgentProfileData::active));
        if (nextActive) deactivateAll();
        AgentProfileData profile = new AgentProfileData(id, required(name, "name"), mode == null ? AgentMode.CHAT : mode,
                blankToNull(modelId), blankToEmpty(systemPrompt), validMaxTurns(maxTurns == null ? defaultMaxTurns : maxTurns),
                nextEnabled, nextActive);
        save(profile);
        return AgentProfile.from(profile);
    }

    public synchronized AgentProfile update(String id, String name, AgentMode mode, String modelId,
                                             String systemPrompt, Integer maxTurns, Boolean enabled, Boolean active) {
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
                maxTurns == null ? current.maxTurns() : validMaxTurns(maxTurns), nextEnabled, nextActive);
        save(updated);
        ensureActive();
        return AgentProfile.from(profiles.get(id));
    }

    public synchronized AgentProfile activate(String id) {
        AgentProfileData target = require(id);
        if (!target.enabled()) throw new IllegalArgumentException("agent profile is disabled: " + id);
        deactivateAll();
        AgentProfileData active = new AgentProfileData(target.id(), target.name(), target.mode(), target.modelId(),
                target.systemPrompt(), target.maxTurns(), true, true);
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
                        profile.systemPrompt(), profile.maxTurns(), profile.enabled(), false));
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
}
