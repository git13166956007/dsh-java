package io.github.git13166956007.dsh.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
}
