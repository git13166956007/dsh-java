package io.github.git13166956007.dsh.tool;

import tools.jackson.databind.JsonNode;

@FunctionalInterface
public interface ToolHandler {
    String execute(JsonNode arguments) throws Exception;
}
