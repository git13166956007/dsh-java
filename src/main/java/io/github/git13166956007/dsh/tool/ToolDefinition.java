package io.github.git13166956007.dsh.tool;

import tools.jackson.databind.node.ObjectNode;
import java.util.Objects;

public final class ToolDefinition {
    private final String name;
    private final String description;
    private final ObjectNode parameters;

    public ToolDefinition(String name, String description, ObjectNode parameters) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.parameters = Objects.requireNonNull(parameters, "parameters").deepCopy();
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public ObjectNode parameters() {
        return parameters.deepCopy();
    }
}
