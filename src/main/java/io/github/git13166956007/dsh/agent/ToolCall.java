package io.github.git13166956007.dsh.agent;

import tools.jackson.databind.JsonNode;

public final class ToolCall {
    private final String id;
    private final String name;
    private final JsonNode arguments;

    public ToolCall(String id, String name, JsonNode arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public JsonNode arguments() {
        return arguments;
    }
}
