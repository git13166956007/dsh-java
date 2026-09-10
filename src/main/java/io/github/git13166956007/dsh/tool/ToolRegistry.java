package io.github.git13166956007.dsh.tool;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {
    private final Map<String, RegisteredTool> tools = new LinkedHashMap<String, RegisteredTool>();

    public synchronized void register(ToolDefinition definition, ToolHandler handler) {
        register(definition, handler, "builtin", false);
    }

    public synchronized void registerCustom(ToolDefinition definition, String result) {
        if (!definition.name().matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("custom tool name must match [A-Za-z0-9_.-]{1,64}");
        }
        if (definition.description().trim().isEmpty()) {
            throw new IllegalArgumentException("custom tool description must not be blank");
        }
        if (result == null) {
            throw new IllegalArgumentException("custom tool result must not be null");
        }
        register(definition, arguments -> result, "custom", true);
    }

    private void register(ToolDefinition definition, ToolHandler handler, String source, boolean removable) {
        if (tools.containsKey(definition.name())) {
            throw new IllegalArgumentException("duplicate tool: " + definition.name());
        }
        tools.put(definition.name(), new RegisteredTool(definition, handler, source, removable));
    }

    public synchronized List<ToolDefinition> definitions() {
        List<ToolDefinition> definitions = new ArrayList<ToolDefinition>();
        for (RegisteredTool tool : tools.values()) {
            if (tool.enabled) definitions.add(tool.definition);
        }
        return definitions;
    }

    public synchronized List<ToolInfo> list() {
        List<ToolInfo> result = new ArrayList<ToolInfo>();
        for (RegisteredTool tool : tools.values()) {
            result.add(new ToolInfo(tool.definition.name(), tool.definition.description(),
                    tool.definition.parameters(), tool.enabled, tool.source, tool.removable));
        }
        return result;
    }

    public synchronized boolean remove(String name) {
        RegisteredTool tool = tools.get(name);
        if (tool == null || !tool.removable) return false;
        tools.remove(name);
        return true;
    }

    public synchronized boolean setEnabled(String name, boolean enabled) {
        RegisteredTool tool = tools.get(name);
        if (tool == null) return false;
        tool.enabled = enabled;
        return true;
    }

    public synchronized String execute(String name, JsonNode arguments) throws Exception {
        RegisteredTool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("unknown tool: " + name);
        if (!tool.enabled) throw new IllegalStateException("tool is disabled: " + name);
        return tool.handler.execute(arguments);
    }

    private static final class RegisteredTool {
        private final ToolDefinition definition;
        private final ToolHandler handler;
        private final String source;
        private final boolean removable;
        private boolean enabled = true;

        private RegisteredTool(ToolDefinition definition, ToolHandler handler, String source, boolean removable) {
            this.definition = definition;
            this.handler = handler;
            this.source = source;
            this.removable = removable;
        }
    }
}
