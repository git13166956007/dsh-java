package io.github.git13166956007.dsh.tool;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ToolRegistry {
    private final Map<String, RegisteredTool> tools = new LinkedHashMap<String, RegisteredTool>();
    private final ToolProfileStore profiles;

    public ToolRegistry() {
        this(null);
    }

    public ToolRegistry(ToolProfileStore profiles) {
        this.profiles = profiles;
        if (profiles != null) {
            try {
                for (ToolProfileData profile : profiles.list()) {
                    register(new ToolDefinition(profile.name(), profile.description(), profile.parameters()),
                            new FixedResultHandler(profile.result()), "custom", true);
                    tools.get(profile.name()).enabled = profile.enabled();
                    tools.get(profile.name()).approvalRequired = profile.approvalRequired();
                }
            } catch (Exception exception) {
                throw new IllegalStateException("failed to load custom tool profiles", exception);
            }
        }
    }

    public synchronized void register(ToolDefinition definition, ToolHandler handler) {
        register(definition, handler, "builtin", false);
    }

    public synchronized void registerCustom(ToolDefinition definition, String result) {
        registerCustom(definition, result, false);
    }

    public synchronized void registerCustom(ToolDefinition definition, String result, boolean approvalRequired) {
        if (definition.description().trim().isEmpty()) {
            throw new IllegalArgumentException("custom tool description must not be blank");
        }
        if (result == null) {
            throw new IllegalArgumentException("custom tool result must not be null");
        }
        register(definition, new FixedResultHandler(result), "custom", true);
        RegisteredTool tool = tools.get(definition.name());
        tool.approvalRequired = approvalRequired;
        persistCustom(definition, result, true, approvalRequired);
    }

    public synchronized void registerExternal(ToolDefinition definition, ToolHandler handler, String source) {
        registerExternal(definition, handler, source, true);
    }

    public synchronized void registerExternal(ToolDefinition definition, ToolHandler handler, String source,
                                              boolean approvalRequired) {
        if (source == null || source.trim().isEmpty()) throw new IllegalArgumentException("source must not be blank");
        register(definition, handler, source.trim(), true);
        tools.get(definition.name()).approvalRequired = approvalRequired;
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
                    tool.definition.parameters(), tool.enabled, tool.source, tool.removable, tool.approvalRequired));
        }
        return result;
    }

    public synchronized boolean remove(String name) {
        RegisteredTool tool = tools.get(name);
        if (tool == null || !tool.removable) return false;
        tools.remove(name);
        deleteProfile(name);
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
        if (tool.removable && "custom".equals(tool.source)) {
            persistCustom(tool.definition, tool.fixedResult, enabled, tool.approvalRequired);
        }
        return true;
    }

    public synchronized boolean setApprovalRequired(String name, boolean required) {
        RegisteredTool tool = tools.get(name);
        if (tool == null) return false;
        tool.approvalRequired = required;
        if (tool.removable && "custom".equals(tool.source)) {
            persistCustom(tool.definition, tool.fixedResult, tool.enabled, required);
        }
        return true;
    }

    public synchronized String execute(String name, JsonNode arguments) throws Exception {
        return execute(name, arguments, null);
    }

    public synchronized String execute(String name, JsonNode arguments, Set<String> allowedNames) throws Exception {
        return execute(name, arguments, allowedNames, false);
    }

    public synchronized String executeApproved(String name, JsonNode arguments, Set<String> allowedNames) throws Exception {
        return execute(name, arguments, allowedNames, true);
    }

    private String execute(String name, JsonNode arguments, Set<String> allowedNames, boolean approvalGranted) throws Exception {
        if (allowedNames != null && !allowedNames.contains(name)) {
            throw new IllegalStateException("tool is not allowed for this agent: " + name);
        }
        RegisteredTool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("unknown tool: " + name);
        if (!tool.enabled) throw new IllegalStateException("tool is disabled: " + name);
        if (tool.approvalRequired && !approvalGranted) throw new ToolApprovalRequiredException(name);
        return tool.handler.execute(arguments);
    }

    private static final class RegisteredTool {
        private final ToolDefinition definition;
        private final ToolHandler handler;
        private final String source;
        private final boolean removable;
        private boolean enabled = true;
        private boolean approvalRequired;
        private final String fixedResult;

        private RegisteredTool(ToolDefinition definition, ToolHandler handler, String source, boolean removable) {
            this.definition = definition;
            this.handler = handler;
            this.source = source;
            this.removable = removable;
            this.fixedResult = handler instanceof FixedResultHandler fixed ? fixed.result : null;
        }
    }

    private void persistCustom(ToolDefinition definition, String result, boolean enabled, boolean approvalRequired) {
        if (profiles == null) return;
        try {
            profiles.save(new ToolProfileData(definition.name(), definition.description(), definition.parameters(), result,
                    enabled, approvalRequired));
        } catch (Exception exception) {
            tools.remove(definition.name());
            throw new IllegalStateException("failed to persist custom tool", exception);
        }
    }

    private void deleteProfile(String name) {
        if (profiles == null) return;
        try {
            profiles.delete(name);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to delete custom tool profile", exception);
        }
    }

    private static final class FixedResultHandler implements ToolHandler {
        private final String result;

        private FixedResultHandler(String result) {
            this.result = result;
        }

        @Override
        public String execute(JsonNode arguments) {
            return result;
        }
    }
}
