package io.github.git13166956007.dsh.agent;

import tools.jackson.databind.JsonNode;

public record AgentTraceEvent(
        String type,
        String name,
        JsonNode arguments,
        String content,
        String result) {
    public static AgentTraceEvent model(String content) {
        return new AgentTraceEvent("model", null, null, content, null);
    }

    public static AgentTraceEvent tool(String name, JsonNode arguments, String result) {
        return new AgentTraceEvent("tool", name, arguments, null, result);
    }
}
