package io.github.git13166956007.dsh.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SkillRegistry {
    private final Path directory;
    private final SkillLoader loader;
    private final SkillStateStore stateStore;
    private final Map<String, SkillInfo> skills = new LinkedHashMap<String, SkillInfo>();

    public SkillRegistry(String configuredDirectory) {
        this(configuredDirectory, null);
    }

    public SkillRegistry(String configuredDirectory, SkillStateStore stateStore) {
        this.directory = configuredDirectory == null || configuredDirectory.isBlank()
                ? Paths.get("skills") : Paths.get(configuredDirectory);
        this.loader = new SkillLoader();
        this.stateStore = stateStore;
        refresh();
    }

    public synchronized List<SkillInfo> list() {
        return new ArrayList<SkillInfo>(skills.values());
    }

    public synchronized SkillInfo find(String id) {
        return skills.get(id);
    }

    public synchronized void refresh() {
        skills.clear();
        if (!Files.isDirectory(directory)) return;
        Map<String, Boolean> persisted = persistedStates();
        try (var paths = Files.list(directory)) {
            paths.filter(Files::isDirectory).sorted().forEach(path -> {
                Path file = path.resolve("SKILL.md");
                if (!Files.isRegularFile(file)) return;
                try {
                    SkillInfo loaded = loader.load(file);
                    Boolean enabled = persisted.get(loaded.id());
                    skills.put(loaded.id(), enabled == null ? loaded : withEnabled(loaded, enabled));
                }
                catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
            // A missing or unreadable skills directory behaves like an empty registry.
        }
    }

    public synchronized SkillInfo setEnabled(String id, boolean enabled) {
        SkillInfo current = require(id);
        SkillInfo updated = withEnabled(current, enabled);
        if (stateStore != null) {
            try {
                stateStore.save(id, enabled);
            } catch (Exception exception) {
                throw new IllegalStateException("failed to persist skill state", exception);
            }
        }
        skills.put(id, updated);
        return updated;
    }

    public synchronized SkillInfo install(String id, String name, String version, String description,
                                          String content, Boolean enabled) throws IOException {
        String normalizedId = normalizeId(id);
        String normalizedContent = required(content, "content");
        if (normalizedContent.length() > 1_000_000) {
            throw new IllegalArgumentException("content must not exceed 1000000 characters");
        }
        Path root = directory.toAbsolutePath().normalize();
        Path skillDirectory = root.resolve(normalizedId).normalize();
        if (!root.equals(skillDirectory.getParent())) {
            throw new IllegalArgumentException("invalid skill id");
        }
        if (Files.exists(skillDirectory) && Files.isSymbolicLink(skillDirectory)) {
            throw new IllegalArgumentException("skill directory must not be a symbolic link");
        }
        Files.createDirectories(root);
        Files.createDirectories(skillDirectory);
        Path skillFile = skillDirectory.resolve("SKILL.md");
        boolean nextEnabled = enabled == null
                ? skills.containsKey(normalizedId) ? skills.get(normalizedId).enabled() : true : enabled;
        String document = frontMatter(name, version, description, nextEnabled) + normalizedContent + "\n";
        Path temporary = Files.createTempFile(root, "." + normalizedId + ".", ".tmp");
        try {
            Files.writeString(temporary, document, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, skillFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, skillFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        refresh();
        return require(normalizedId);
    }

    public synchronized boolean remove(String id) throws IOException {
        String normalizedId = normalizeId(id);
        Path skillDirectory = directory.toAbsolutePath().normalize().resolve(normalizedId).normalize();
        if (!Files.isDirectory(skillDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.isSymbolicLink(skillDirectory)) {
            throw new IllegalArgumentException("skill directory must not be a symbolic link");
        }
        try (var paths = Files.walk(skillDirectory)) {
            List<Path> all = paths.peek(path -> {
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("skill resources must not contain symbolic links");
            }).sorted(Comparator.reverseOrder()).toList();
            for (Path path : all) Files.deleteIfExists(path);
        }
        skills.remove(normalizedId);
        if (stateStore != null) stateStore.delete(normalizedId);
        return true;
    }

    public synchronized String systemPrompt() {
        return systemPrompt(null);
    }

    public synchronized String systemPrompt(Set<String> allowedIds) {
        StringBuilder prompt = new StringBuilder("You are a helpful assistant. Use available tools when they are useful, then give a concise final answer.");
        for (SkillInfo skill : skills.values()) {
            if (!skill.enabled() || (allowedIds != null && !allowedIds.contains(skill.id()))) continue;
            prompt.append("\n\n## Skill: ").append(skill.name()).append("\n").append(skill.content());
        }
        return prompt.toString();
    }

    public Path directory() {
        return directory;
    }

    private SkillInfo require(String id) {
        SkillInfo skill = skills.get(id);
        if (skill == null) throw new IllegalArgumentException("unknown skill: " + id);
        return skill;
    }

    private Map<String, Boolean> persistedStates() {
        if (stateStore == null) return Map.of();
        try {
            return stateStore.list();
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load skill states", exception);
        }
    }

    private static SkillInfo withEnabled(SkillInfo skill, boolean enabled) {
        return new SkillInfo(skill.id(), skill.name(), skill.version(), skill.description(), enabled,
                skill.content(), skill.resources());
    }

    private static String normalizeId(String value) {
        String normalized = required(value, "id");
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("id must contain only letters, numbers, '_' or '-'");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String singleLine(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (normalized.contains("\r") || normalized.contains("\n")) {
            throw new IllegalArgumentException("skill metadata must be single-line");
        }
        return normalized;
    }

    private static String frontMatter(String name, String version, String description, boolean enabled) {
        return "---\n"
                + "name: " + singleLine(name, "Skill") + "\n"
                + "version: " + singleLine(version, "0.1.0") + "\n"
                + "description: " + singleLine(description, "") + "\n"
                + "enabled: " + enabled + "\n"
                + "---\n\n";
    }
}
