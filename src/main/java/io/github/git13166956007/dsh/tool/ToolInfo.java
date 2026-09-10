package io.github.git13166956007.dsh.tool;

import tools.jackson.databind.node.ObjectNode;

public record ToolInfo(String name, String description, ObjectNode parameters, boolean enabled,
                       String source, boolean removable) {
}
