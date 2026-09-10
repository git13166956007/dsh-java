package io.github.git13166956007.dsh.agent;

import tools.jackson.databind.JsonNode;

public record PendingToolApproval(String toolCallId, String toolName, JsonNode arguments) {
}
