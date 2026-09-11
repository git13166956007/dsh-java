package io.github.git13166956007.dsh.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

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

    @Test
    void doesNotHoldRegistryLockWhileToolRuns() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        registry.register(new ToolDefinition("blocking", "Blocking tool.",
                JsonNodeFactory.instance.objectNode().put("type", "object")), arguments -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return "done";
        });

        CompletableFuture<String> execution = CompletableFuture.supplyAsync(() -> {
            try {
                return registry.execute("blocking", JsonNodeFactory.instance.objectNode());
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        assertEquals(true, started.await(1, TimeUnit.SECONDS));
        registry.setEnabled("blocking", false);
        release.countDown();
        assertEquals("done", execution.get(2, TimeUnit.SECONDS));
    }

    @Test
    void validatesToolArgumentsAgainstSchemaBeforeExecution() {
        ToolRegistry registry = new ToolRegistry();
        ObjectNode schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        schema.putObject("properties").putObject("query").put("type", "string");
        schema.putArray("required").add("query");
        schema.put("additionalProperties", false);
        registry.register(new ToolDefinition("search", "Search.", schema), arguments -> "ok");

        ObjectNode valid = JsonNodeFactory.instance.objectNode().put("query", "kernel");
        assertDoesNotThrow(() -> registry.validateArguments("search", valid, null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.validateArguments("search", JsonNodeFactory.instance.objectNode(), null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.validateArguments("search", JsonNodeFactory.instance.objectNode().put("query", 1), null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.validateArguments("search", JsonNodeFactory.instance.objectNode().put("query", "x")
                        .put("extra", true), null));
    }

    @Test
    void definitionSchemaIsDefensivelyCopied() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        ToolDefinition definition = new ToolDefinition("immutable_schema", "Schema.", schema);
        schema.put("additionalProperties", false);
        assertEquals(false, definition.parameters().has("additionalProperties"));
        definition.parameters().put("additionalProperties", false);
        assertEquals(false, definition.parameters().has("additionalProperties"));
    }
}
