package io.github.git13166956007.dsh.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SubAgentProfileRegistry {
    private final SubAgentProfileStore store;
    private final int defaultMaxTurns;
    private final Map<String, SubAgentProfileData> profiles = new LinkedHashMap<String, SubAgentProfileData>();

    public SubAgentProfileRegistry(SubAgentProfileStore store, int defaultMaxTurns) {
        if (defaultMaxTurns < 1) throw new IllegalArgumentException("defaultMaxTurns must be positive");
        this.store = store;
        this.defaultMaxTurns = defaultMaxTurns;
        try { profiles.putAll(index(store.list())); }
        catch (Exception exception) { throw new IllegalStateException("failed to load sub-agent profiles", exception); }
    }

    public synchronized List<SubAgentProfile> list() {
        return profiles.values().stream().map(SubAgentProfile::from).toList();
    }

    public synchronized SubAgentProfile find(String id) {
        SubAgentProfileData profile = profiles.get(id);
        return profile == null ? null : SubAgentProfile.from(profile);
    }

    public synchronized SubAgentProfileData resolve(String id) {
        SubAgentProfileData profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("unknown sub-agent profile: " + id);
        if (!profile.enabled()) throw new IllegalStateException("sub-agent profile is disabled: " + id);
        return profile;
    }

    public synchronized SubAgentProfile create(String name, AgentMode mode, String modelId, String systemPrompt,
                                                Integer maxTurns, List<String> allowedToolNames, List<String> skillIds,
                                                Boolean enabled) {
        return create(name, mode, modelId, systemPrompt, maxTurns, allowedToolNames, skillIds, enabled, 64, 300, 4);
    }

    public synchronized SubAgentProfile create(String name, AgentMode mode, String modelId, String systemPrompt,
                                                Integer maxTurns, List<String> allowedToolNames, List<String> skillIds,
                                                Boolean enabled, Integer maxToolCalls, Integer timeoutSeconds,
                                                Integer maxDepth) {
        SubAgentProfileData profile = new SubAgentProfileData(UUID.randomUUID().toString(), required(name, "name"),
                mode == null ? AgentMode.CHAT : mode, blankToNull(modelId), systemPrompt == null ? "" : systemPrompt.trim(),
                validMaxTurns(maxTurns == null ? defaultMaxTurns : maxTurns), normalizeList(allowedToolNames, "tool"),
                normalizeList(skillIds, "skill"), enabled == null || enabled,
                validMaxToolCalls(maxToolCalls == null ? 64 : maxToolCalls),
                validTimeoutSeconds(timeoutSeconds == null ? 300 : timeoutSeconds),
                validMaxDepth(maxDepth == null ? 4 : maxDepth));
        save(profile);
        return SubAgentProfile.from(profile);
    }

    public synchronized SubAgentProfile update(String id, String name, AgentMode mode, String modelId,
                                                String systemPrompt, Integer maxTurns, List<String> allowedToolNames,
                                                List<String> skillIds, Boolean enabled) {
        return update(id, name, mode, modelId, systemPrompt, maxTurns, allowedToolNames, skillIds, enabled,
                null, null, null);
    }

    public synchronized SubAgentProfile update(String id, String name, AgentMode mode, String modelId,
                                                String systemPrompt, Integer maxTurns, List<String> allowedToolNames,
                                                List<String> skillIds, Boolean enabled, Integer maxToolCalls,
                                                Integer timeoutSeconds, Integer maxDepth) {
        SubAgentProfileData current = resolveExisting(id);
        SubAgentProfileData updated = new SubAgentProfileData(id, name == null ? current.name() : required(name, "name"),
                mode == null ? current.mode() : mode, modelId == null ? current.modelId() : blankToNull(modelId),
                systemPrompt == null ? current.systemPrompt() : systemPrompt.trim(),
                maxTurns == null ? current.maxTurns() : validMaxTurns(maxTurns),
                allowedToolNames == null ? current.allowedToolNames() : normalizeList(allowedToolNames, "tool"),
                skillIds == null ? current.skillIds() : normalizeList(skillIds, "skill"),
                enabled == null ? current.enabled() : enabled,
                maxToolCalls == null ? current.maxToolCalls() : validMaxToolCalls(maxToolCalls),
                timeoutSeconds == null ? current.timeoutSeconds() : validTimeoutSeconds(timeoutSeconds),
                maxDepth == null ? current.maxDepth() : validMaxDepth(maxDepth));
        save(updated);
        return SubAgentProfile.from(updated);
    }

    public synchronized boolean delete(String id) {
        if (!profiles.containsKey(id)) return false;
        try { store.delete(id); profiles.remove(id); return true; }
        catch (Exception exception) { throw new IllegalStateException("failed to delete sub-agent profile", exception); }
    }

    private SubAgentProfileData resolveExisting(String id) {
        SubAgentProfileData profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("unknown sub-agent profile: " + id);
        return profile;
    }

    private void save(SubAgentProfileData profile) {
        try { store.save(profile); profiles.put(profile.id(), profile); }
        catch (Exception exception) { throw new IllegalStateException("failed to save sub-agent profile", exception); }
    }

    private static Map<String, SubAgentProfileData> index(List<SubAgentProfileData> values) {
        Map<String, SubAgentProfileData> result = new LinkedHashMap<String, SubAgentProfileData>();
        for (SubAgentProfileData profile : values) result.put(profile.id(), profile);
        return result;
    }

    private static List<String> normalizeList(List<String> values, String type) {
        if (values == null) return List.of();
        List<String> result = new ArrayList<String>();
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized == null || !normalized.matches("[A-Za-z0-9_-]{1,128}")) {
                throw new IllegalArgumentException("invalid " + type + " name");
            }
            if (!result.contains(normalized)) result.add(normalized);
        }
        return List.copyOf(result);
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
}
