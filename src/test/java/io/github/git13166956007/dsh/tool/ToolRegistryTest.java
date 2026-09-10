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
        registry.registerCustom(new ToolDefinition("demo_custom", "Custom tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), "custom-result");

        assertEquals("custom-result", registry.execute("demo_custom", JsonNodeFactory.instance.objectNode()));
        assertEquals(true, registry.list().get(0).removable());
        assertEquals(true, registry.remove("demo_custom"));
    }

    @Test
    void customToolProfilesSurviveRegistryRecreation() throws Exception {
        InMemoryToolProfileStore store = new InMemoryToolProfileStore();
        ToolRegistry first = new ToolRegistry(store);
        first.registerCustom(new ToolDefinition("persistent_demo", "Persistent tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), "saved-result");
        first.setEnabled("persistent_demo", false);

        ToolRegistry second = new ToolRegistry(store);
        assertEquals(false, second.list().get(0).enabled());
        assertEquals("saved-result", second.list().get(0).name().equals("persistent_demo")
                ? store.list().get(0).result() : "");
    }

    @Test
    void approvalPolicyBlocksExecutionAndSurvivesRegistryRecreation() throws Exception {
        InMemoryToolProfileStore store = new InMemoryToolProfileStore();
        ToolRegistry first = new ToolRegistry(store);
        first.registerCustom(new ToolDefinition("approval_demo", "Approval tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), "saved-result", true);

        assertEquals(true, first.list().get(0).approvalRequired());
        assertThrows(ToolApprovalRequiredException.class,
                () -> first.execute("approval_demo", JsonNodeFactory.instance.objectNode()));

        ToolRegistry second = new ToolRegistry(store);
        assertEquals(true, second.list().get(0).approvalRequired());
        second.setApprovalRequired("approval_demo", false);
        assertEquals("saved-result", second.execute("approval_demo", JsonNodeFactory.instance.objectNode()));
    }
}
