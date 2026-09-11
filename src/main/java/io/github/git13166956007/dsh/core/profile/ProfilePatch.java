package io.github.git13166956007.dsh.core.profile;

import java.util.Map;
import java.util.Set;

/** A sparse profile overlay applied at a scope boundary. */
public record ProfilePatch(
        String modelId,
        String systemPrompt,
        Set<String> allowedToolNames,
        Set<String> allowedSkillIds,
        Map<String, String> permissions) {
    public RuntimeProfile apply(RuntimeProfile base, String id) {
        RuntimeProfile source = base == null ? RuntimeProfile.empty(id) : base;
        return new RuntimeProfile(
                id == null || id.isBlank() ? source.id() : id,
                source.parentId(),
                modelId == null ? source.modelId() : modelId,
                systemPrompt == null ? source.systemPrompt() : systemPrompt,
                allowedToolNames == null ? source.allowedToolNames() : allowedToolNames,
                allowedSkillIds == null ? source.allowedSkillIds() : allowedSkillIds,
                permissions == null ? source.permissions() : permissions);
    }
}
