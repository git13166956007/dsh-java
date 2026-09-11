package io.github.git13166956007.dsh.security;

import java.util.Map;
import tools.jackson.databind.JsonNode;

public record PolicyRequest(
        String runId,
        String agentId,
        String toolName,
        JsonNode arguments,
        Map<String, String> permissions,
        boolean approvalGranted) {
    public PolicyRequest(String runId, String agentId, String toolName, JsonNode arguments,
                         Map<String, String> permissions) {
        this(runId, agentId, toolName, arguments, permissions, false);
    }

    public PolicyRequest {
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("tool name is required");
        arguments = arguments == null ? null : arguments.deepCopy();
        permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
    }
}
