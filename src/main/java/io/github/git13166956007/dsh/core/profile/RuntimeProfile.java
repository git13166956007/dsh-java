package io.github.git13166956007.dsh.core.profile;

import java.util.Map;
import java.util.Set;

/** Immutable capability and prompt selection for one runtime scope. */
public record RuntimeProfile(
        String id,
        String parentId,
        String modelId,
        String systemPrompt,
        Set<String> allowedToolNames,
        Set<String> allowedSkillIds,
        Map<String, String> permissions) {
    public RuntimeProfile {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("profile id must not be blank");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        allowedToolNames = allowedToolNames == null ? Set.of() : Set.copyOf(allowedToolNames);
        allowedSkillIds = allowedSkillIds == null ? Set.of() : Set.copyOf(allowedSkillIds);
        permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
    }

    public static RuntimeProfile empty(String id) {
        return new RuntimeProfile(id, null, null, "", Set.of(), Set.of(), Map.of());
    }
}
