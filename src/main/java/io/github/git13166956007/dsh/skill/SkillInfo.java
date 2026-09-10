package io.github.git13166956007.dsh.skill;

import java.util.List;

public record SkillInfo(String id, String name, String version, String description, boolean enabled,
                        String content, List<String> resources) {
    public SkillInfo {
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    public SkillInfo(String id, String name, String description, boolean enabled, String content) {
        this(id, name, "0.1.0", description, enabled, content, List.of());
    }
}
