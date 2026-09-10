package io.github.git13166956007.dsh.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class ToolRegistryTest {
    @Test
    void disabledToolsAreHiddenAndCannotExecute() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolDefinition("demo", "Demo tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> "ok");

        assertEquals(List.of("demo"), registry.definitions().stream().map(ToolDefinition::name).toList());
        registry.setEnabled("demo", false);
        assertEquals(List.of(), registry.definitions());
        assertThrows(IllegalStateException.class,
                () -> registry.execute("demo", JsonNodeFactory.instance.objectNode()));
    }

    @Test
    void customToolsCanBeAddedAndRemoved() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.registerCustom(new ToolDefinition("demo.custom", "Custom tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), "custom-result");

        assertEquals("custom-result", registry.execute("demo.custom", JsonNodeFactory.instance.objectNode()));
        assertEquals(true, registry.list().get(0).removable());
        assertEquals(true, registry.remove("demo.custom"));
    }
}
