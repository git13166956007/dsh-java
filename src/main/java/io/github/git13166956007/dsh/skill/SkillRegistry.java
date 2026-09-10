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
    private final Map<String, SkillInfo> skills = new LinkedHashMap<String, SkillInfo>();

    public SkillRegistry(String configuredDirectory) {
        this.directory = configuredDirectory == null || configuredDirectory.isBlank()
                ? Paths.get("skills") : Paths.get(configuredDirectory);
        this.loader = new SkillLoader();
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
        try (var paths = Files.list(directory)) {
            paths.filter(Files::isDirectory).sorted().forEach(path -> {
                Path file = path.resolve("SKILL.md");
                if (!Files.isRegularFile(file)) return;
                try { skills.put(path.getFileName().toString(), loader.load(file)); }
                catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
            // A missing or unreadable skills directory behaves like an empty registry.
        }
    }

    public synchronized SkillInfo setEnabled(String id, boolean enabled) {
        SkillInfo current = require(id);
        SkillInfo updated = new SkillInfo(current.id(), current.name(), current.description(), enabled, current.content());
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
}
