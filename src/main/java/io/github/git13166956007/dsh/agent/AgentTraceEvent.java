package io.github.git13166956007.dsh.agent;

import tools.jackson.databind.JsonNode;

public record AgentTraceEvent(
        String type,
        String name,
        JsonNode arguments,
        String content,
        String result,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {
    public AgentTraceEvent(String type, String name, JsonNode arguments, String content, String result) {
        this(type, name, arguments, content, result, null, null, null);
    }

    public static AgentTraceEvent model(String content) {
        return model(content, null);
    }

    public static AgentTraceEvent model(String content, ModelResponse response) {
        return new AgentTraceEvent("model", null, null, content, null,
                response == null ? null : response.promptTokens(),
                response == null ? null : response.completionTokens(),
                response == null ? null : response.totalTokens());
    }

    public static AgentTraceEvent tool(String name, JsonNode arguments, String result) {
        return new AgentTraceEvent("tool", name, arguments, null, result);
    }
}
