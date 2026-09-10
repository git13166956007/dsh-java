package io.github.git13166956007.dsh.tool;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {
    private final Map<String, RegisteredTool> tools = new LinkedHashMap<String, RegisteredTool>();

    public synchronized void register(ToolDefinition definition, ToolHandler handler) {
        if (tools.containsKey(definition.name())) {
            throw new IllegalArgumentException("duplicate tool: " + definition.name());
        }
        tools.put(definition.name(), new RegisteredTool(definition, handler));
    }

    public synchronized List<ToolDefinition> definitions() {
        List<ToolDefinition> definitions = new ArrayList<ToolDefinition>();
        for (RegisteredTool tool : tools.values()) definitions.add(tool.definition);
        return definitions;
    }

    public synchronized String execute(String name, JsonNode arguments) throws Exception {
        RegisteredTool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("unknown tool: " + name);
        return tool.handler.execute(arguments);
    }

    private static final class RegisteredTool {
        private final ToolDefinition definition;
        private final ToolHandler handler;

        private RegisteredTool(ToolDefinition definition, ToolHandler handler) {
            this.definition = definition;
            this.handler = handler;
        }
    }
}
