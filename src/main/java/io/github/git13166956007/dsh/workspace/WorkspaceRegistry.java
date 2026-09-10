package io.github.git13166956007.dsh.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class WorkspaceRegistry {
    private final WorkspaceStore store;
    private final Map<String, WorkspaceProfileData> profiles = new LinkedHashMap<String, WorkspaceProfileData>();

    public WorkspaceRegistry(WorkspaceStore store, String directory, long maxReadBytes, long maxWriteBytes,
                             boolean writeEnabled, int maxProcessTimeoutSeconds, long maxProcessOutputBytes,
                             Set<String> allowedCommands) {
        this.store = store;
        try {
            profiles.putAll(index(store.list()));
            if (profiles.isEmpty()) {
                WorkspaceProfileData fallback = new WorkspaceProfileData("default", "Default workspace",
                        normalizeDirectory(directory), true, true, writeEnabled, validBytes(maxReadBytes, "maxReadBytes"),
                        validBytes(maxWriteBytes, "maxWriteBytes"), validTimeout(maxProcessTimeoutSeconds),
                        validBytes(maxProcessOutputBytes, "maxProcessOutputBytes"), normalizeCommands(allowedCommands));
                profiles.put(fallback.id(), fallback);
                store.save(fallback);
            } else {
                normalizeActiveSelection();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load workspaces", exception);
        }
    }

    public synchronized List<WorkspaceProfile> list() {
        return profiles.values().stream()
                .sorted(Comparator.comparing(WorkspaceProfileData::active).reversed().thenComparing(WorkspaceProfileData::name))
                .map(WorkspaceProfile::from).toList();
    }

    public synchronized WorkspaceProfile find(String id) {
        WorkspaceProfileData profile = profiles.get(id);
        return profile == null ? null : WorkspaceProfile.from(profile);
    }

    public synchronized WorkspaceProfile active() {
        return profiles.values().stream().filter(profile -> profile.enabled() && profile.active()).findFirst()
                .or(() -> profiles.values().stream().filter(WorkspaceProfileData::enabled).findFirst())
                .map(WorkspaceProfile::from)
                .orElseThrow(() -> new IllegalStateException("no enabled workspace is active"));
    }

    public synchronized WorkspaceProfile create(String name, String directory, Boolean enabled, Boolean active,
                                                 Boolean writeEnabled, Long maxReadBytes, Long maxWriteBytes,
                                                 Integer maxProcessTimeoutSeconds, Long maxProcessOutputBytes,
                                                 Set<String> allowedCommands) {
        String id = UUID.randomUUID().toString();
        boolean nextEnabled = enabled == null || enabled;
        boolean nextActive = nextEnabled && (Boolean.TRUE.equals(active)
                || profiles.values().stream().noneMatch(profile -> profile.active() && profile.enabled()));
        WorkspaceProfileData profile = new WorkspaceProfileData(id, required(name, "name"), normalizeDirectory(directory),
                nextEnabled, nextActive, Boolean.TRUE.equals(writeEnabled),
                validBytes(maxReadBytes == null ? 1_000_000 : maxReadBytes, "maxReadBytes"),
                validBytes(maxWriteBytes == null ? 1_000_000 : maxWriteBytes, "maxWriteBytes"),
                validTimeout(maxProcessTimeoutSeconds == null ? 120 : maxProcessTimeoutSeconds),
                validBytes(maxProcessOutputBytes == null ? 1_000_000 : maxProcessOutputBytes, "maxProcessOutputBytes"),
                normalizeCommands(allowedCommands));
        if (profiles.values().stream().anyMatch(item -> item.name().equalsIgnoreCase(profile.name()))) {
            throw new IllegalArgumentException("workspace name already exists: " + profile.name());
        }
        if (nextActive) deactivateAll();
        save(profile);
        ensureActive();
        return WorkspaceProfile.from(profiles.get(id));
    }

    public synchronized WorkspaceProfile update(String id, JsonNode patch) {
        if (patch == null || !patch.isObject()) throw new IllegalArgumentException("workspace patch must be a JSON object");
        WorkspaceProfileData current = require(id);
        String name = text(patch, "name", current.name());
        if (!name.equalsIgnoreCase(current.name()) && profiles.values().stream()
                .anyMatch(item -> item.name().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("workspace name already exists: " + name);
        }
        boolean enabled = booleanValue(patch, "enabled", current.enabled());
        boolean active = enabled && booleanValue(patch, "active", current.active());
        WorkspaceProfileData updated = new WorkspaceProfileData(id, required(name, "name"),
                patch.has("directory") ? normalizeDirectory(text(patch, "directory", current.directory())) : current.directory(),
                enabled, active, booleanValue(patch, "writeEnabled", current.writeEnabled()),
                longValue(patch, "maxReadBytes", current.maxReadBytes(), "maxReadBytes"),
                longValue(patch, "maxWriteBytes", current.maxWriteBytes(), "maxWriteBytes"),
                intValue(patch, "maxProcessTimeoutSeconds", current.maxProcessTimeoutSeconds(), "maxProcessTimeoutSeconds"),
                longValue(patch, "maxProcessOutputBytes", current.maxProcessOutputBytes(), "maxProcessOutputBytes"),
                patch.has("allowedCommands") ? normalizeCommands(patch.path("allowedCommands")) : current.allowedCommands());
        if (active) deactivateAll();
        save(updated);
        ensureActive();
        return WorkspaceProfile.from(profiles.get(id));
    }

    public synchronized WorkspaceProfile activate(String id) {
        WorkspaceProfileData target = require(id);
        if (!target.enabled()) throw new IllegalArgumentException("workspace is disabled: " + id);
        deactivateAll();
        WorkspaceProfileData active = copy(target, true, true);
        save(active);
        return WorkspaceProfile.from(active);
    }

    public synchronized boolean delete(String id) {
        if (!profiles.containsKey(id)) return false;
        profiles.remove(id);
        try {
            store.delete(id);
            ensureActive();
            return true;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to delete workspace", exception);
        }
    }

    private void normalizeActiveSelection() {
        boolean retained = false;
        for (WorkspaceProfileData profile : new ArrayList<WorkspaceProfileData>(profiles.values())) {
            if (profile.active() && profile.enabled() && !retained) {
                retained = true;
            } else if (profile.active()) {
                save(copy(profile, profile.enabled(), false));
            }
        }
        if (!retained) ensureActive();
    }

    private void deactivateAll() {
        for (WorkspaceProfileData profile : new ArrayList<WorkspaceProfileData>(profiles.values())) {
            if (profile.active()) save(copy(profile, profile.enabled(), false));
        }
    }

    private void ensureActive() {
        if (profiles.values().stream().anyMatch(profile -> profile.active() && profile.enabled())) return;
        profiles.values().stream().filter(WorkspaceProfileData::enabled).findFirst().ifPresent(profile ->
                save(copy(profile, true, true)));
    }

    private void save(WorkspaceProfileData profile) {
        try {
            store.save(profile);
            profiles.put(profile.id(), profile);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to save workspace", exception);
        }
    }

    private WorkspaceProfileData require(String id) {
        WorkspaceProfileData profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("unknown workspace: " + id);
        return profile;
    }

    private static WorkspaceProfileData copy(WorkspaceProfileData value, boolean enabled, boolean active) {
        return new WorkspaceProfileData(value.id(), value.name(), value.directory(), enabled, active,
                value.writeEnabled(), value.maxReadBytes(), value.maxWriteBytes(), value.maxProcessTimeoutSeconds(),
                value.maxProcessOutputBytes(), value.allowedCommands());
    }

    private static Map<String, WorkspaceProfileData> index(List<WorkspaceProfileData> values) {
        Map<String, WorkspaceProfileData> result = new LinkedHashMap<String, WorkspaceProfileData>();
        for (WorkspaceProfileData value : values) result.put(value.id(), value);
        return result;
    }

    private static String normalizeDirectory(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("directory must not be blank");
        try {
            return Files.createDirectories(Path.of(value).toAbsolutePath().normalize()).toRealPath().toString();
        } catch (Exception exception) {
            throw new IllegalArgumentException("workspace directory is not usable: " + value, exception);
        }
    }

    private static Set<String> normalizeCommands(Set<String> values) {
        Set<String> result = new LinkedHashSet<String>();
        if (values == null) return result;
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String command = value.trim();
            if (command.contains("/") || command.contains("\\") || !command.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("invalid allowlisted command: " + command);
            }
            result.add(command);
        }
        return Set.copyOf(result);
    }

    private static Set<String> normalizeCommands(JsonNode values) {
        if (values == null || values.isNull()) return Set.of();
        if (!values.isArray()) throw new IllegalArgumentException("allowedCommands must be an array");
        Set<String> commands = new LinkedHashSet<String>();
        for (JsonNode value : values) commands.add(value.asText(null));
        return normalizeCommands(commands);
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String text(JsonNode patch, String field, String fallback) {
        return patch.has(field) && !patch.path(field).isNull() ? patch.path(field).asText() : fallback;
    }

    private static boolean booleanValue(JsonNode patch, String field, boolean fallback) {
        return patch.has(field) && !patch.path(field).isNull() ? patch.path(field).asBoolean() : fallback;
    }

    private static long longValue(JsonNode patch, String field, long fallback, String label) {
        long value = patch.has(field) && !patch.path(field).isNull() ? patch.path(field).asLong(-1) : fallback;
        return validBytes(value, label);
    }

    private static int intValue(JsonNode patch, String field, int fallback, String label) {
        int value = patch.has(field) && !patch.path(field).isNull() ? patch.path(field).asInt(-1) : fallback;
        if (value < 1 || value > 3600) throw new IllegalArgumentException(label + " must be between 1 and 3600");
        return value;
    }

    private static long validBytes(long value, String label) {
        if (value < 1 || value > 50_000_000) throw new IllegalArgumentException(label + " must be between 1 and 50000000");
        return value;
    }

    private static int validTimeout(int value) {
        if (value < 1 || value > 3600) throw new IllegalArgumentException("maxProcessTimeoutSeconds must be between 1 and 3600");
        return value;
    }
}
