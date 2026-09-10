package io.github.git13166956007.dsh.tool;

import tools.jackson.databind.node.ObjectNode;

record ToolProfileData(String name, String description, ObjectNode parameters, String result, boolean enabled,
                       boolean approvalRequired) {
}
