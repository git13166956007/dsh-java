package io.github.git13166956007.dsh.context;

public record ContextRequest(String conversationId, String query, String modelId, String agentId, String mode) {
    public ContextRequest {
        query = query == null ? "" : query;
        mode = mode == null ? "" : mode;
    }
}
