package io.github.git13166956007.dsh.tool;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ToolRegistry {
    private final Map<String, RegisteredTool> tools = new LinkedHashMap<String, RegisteredTool>();

    public synchronized void register(ToolDefinition definition, ToolHandler handler) {
        register(definition, handler, "builtin", false);
    }

    public synchronized void registerCustom(ToolDefinition definition, String result) {
        if (definition.description().trim().isEmpty()) {
            throw new IllegalArgumentException("custom tool description must not be blank");
        }
        if (result == null) {
            throw new IllegalArgumentException("custom tool result must not be null");
        }
        register(definition, arguments -> result, "custom", true);
    }

    public synchronized void registerExternal(ToolDefinition definition, ToolHandler handler, String source) {
        if (source == null || source.trim().isEmpty()) throw new IllegalArgumentException("source must not be blank");
        register(definition, handler, source.trim(), true);
    }

    private void register(ToolDefinition definition, ToolHandler handler, String source, boolean removable) {
        if (!definition.name().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("tool name must match [A-Za-z0-9_-]{1,64}");
        }
        if (tools.containsKey(definition.name())) {
            throw new IllegalArgumentException("duplicate tool: " + definition.name());
        }
        tools.put(definition.name(), new RegisteredTool(definition, handler, source, removable));
    }

    public synchronized List<ToolDefinition> definitions() {
        return definitions(null);
    }

    public synchronized List<ToolDefinition> definitions(Set<String> allowedNames) {
        List<ToolDefinition> definitions = new ArrayList<ToolDefinition>();
        for (RegisteredTool tool : tools.values()) {
            if (tool.enabled && (allowedNames == null || allowedNames.contains(tool.definition.name()))) {
                definitions.add(tool.definition);
            }
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

    public synchronized int removeBySource(String source) {
        int removed = 0;
        var iterator = tools.entrySet().iterator();
        while (iterator.hasNext()) {
            RegisteredTool tool = iterator.next().getValue();
            if (tool.removable && tool.source.equals(source)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public synchronized boolean setEnabled(String name, boolean enabled) {
        RegisteredTool tool = tools.get(name);
        if (tool == null) return false;
        tool.enabled = enabled;
        return true;
    }

    public synchronized String execute(String name, JsonNode arguments) throws Exception {
        return execute(name, arguments, null);
    }

    public synchronized String execute(String name, JsonNode arguments, Set<String> allowedNames) throws Exception {
        if (allowedNames != null && !allowedNames.contains(name)) {
            throw new IllegalStateException("tool is not allowed for this agent: " + name);
        }
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
