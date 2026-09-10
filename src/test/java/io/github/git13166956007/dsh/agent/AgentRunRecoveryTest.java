package io.github.git13166956007.dsh.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.git13166956007.dsh.context.ContextManager;
import io.github.git13166956007.dsh.context.InMemoryConversationStore;
import io.github.git13166956007.dsh.model.InMemoryModelProfileStore;
import io.github.git13166956007.dsh.model.ModelRegistry;
import io.github.git13166956007.dsh.run.InMemoryRunStore;
import io.github.git13166956007.dsh.run.Run;
import io.github.git13166956007.dsh.run.RunKind;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.run.RunSpec;
import io.github.git13166956007.dsh.run.RunStatus;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AgentRunRecoveryTest {
    @Test
    void reattachesRunningSubAgentAndWritesRecoveredAnswerToSession() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InMemoryAgentContinuationStore continuations = new InMemoryAgentContinuationStore();
        InMemoryRunStore runStore = new InMemoryRunStore();
        RunManager runs = new RunManager(runStore);
        SubAgentProfileRegistry profiles = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 2);
        SubAgentProfile profile = profiles.create("Recoverable worker", AgentMode.EXECUTION, null, "", 2,
                List.of(), List.of(), true);
        ContextManager contexts = new ContextManager(new InMemoryConversationStore(), 20, 10_000);
        ModelRegistry models = new ModelRegistry(new InMemoryModelProfileStore(), "http://localhost", "deepseek",
                "test-model", null, "", 0);
        AgentLoop loop = new AgentLoop((messages, definitions) -> new ModelResponse("recovered answer", List.of(), "stop"),
                new ToolRegistry(), null, null, null, runs, continuations, mapper, 2);
        SubAgentRunner runner = new SubAgentRunner(loop, profiles);
        SubAgentSessionManager sessions = new SubAgentSessionManager(new InMemorySubAgentSessionStore(), contexts,
                runner, profiles, models, runs);
        SubAgentSession session = sessions.create(profile.id());
        Run run = runs.find(runs.start(new RunSpec(null, RunKind.SUB_AGENT, session.conversationId(), null, null,
                profile.id(), null)));
        continuations.save(run.id(), asyncRequest(mapper));
        AgentRunRecovery recovery = new AgentRunRecovery(continuations, runs, loop, profiles, sessions);
        try {
            recovery.recover();
            waitForStatus(runs, run.id(), RunStatus.COMPLETED);
            assertEquals("recovered answer", runs.find(run.id()).output());
            waitForAssistant(contexts, session.conversationId());
        } finally {
            loop.close();
        }
    }

    private static String asyncRequest(ObjectMapper mapper) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("kind", "async_request");
        root.put("prompt", "recover this task");
        ObjectNode options = root.putObject("options");
        options.putNull("modelId");
        options.put("mode", "EXECUTION");
        options.put("systemPrompt", "");
        options.put("maxTurns", 2);
        options.put("maxToolCalls", 64);
        options.put("timeoutSeconds", 300);
        options.put("maxDepth", 4);
        options.putNull("memoryNamespace");
        options.putNull("memorySubjectKey");
        options.putNull("agentId");
        options.putNull("allowedToolNames");
        options.putNull("skillIds");
        root.putArray("history");
        return mapper.writeValueAsString(root);
    }

    private static void waitForStatus(RunManager runs, String id, RunStatus expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (runs.find(id).status() == expected) return;
            Thread.sleep(10);
        }
        assertEquals(expected, runs.find(id).status());
    }

    private static void waitForAssistant(ContextManager contexts, String conversationId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (contexts.history(conversationId).stream().anyMatch(message ->
                    message.role() == ChatMessage.Role.ASSISTANT && "recovered answer".equals(message.content()))) return;
            Thread.sleep(10);
        }
        assertTrue(contexts.history(conversationId).stream().anyMatch(message ->
                message.role() == ChatMessage.Role.ASSISTANT && "recovered answer".equals(message.content())));
    }
}
