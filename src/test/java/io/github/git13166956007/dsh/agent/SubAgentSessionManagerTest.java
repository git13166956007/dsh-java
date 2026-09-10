package io.github.git13166956007.dsh.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.git13166956007.dsh.context.ContextManager;
import io.github.git13166956007.dsh.context.InMemoryConversationStore;
import io.github.git13166956007.dsh.model.InMemoryModelProfileStore;
import io.github.git13166956007.dsh.model.ModelRegistry;
import io.github.git13166956007.dsh.run.InMemoryRunStore;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.run.RunStatus;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubAgentSessionManagerTest {
    @Test
    void continuesTheSamePersistentConversationAcrossRuns() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<List<ChatMessage>>();
        ChatModel model = (messages, definitions) -> {
            requests.add(List.copyOf(messages));
            return new ModelResponse("answer-" + requests.size(), List.of(), "stop");
        };
        RunManager runs = new RunManager(new InMemoryRunStore());
        AgentLoop loop = new AgentLoop(model, new ToolRegistry(), null, null, null, runs, null, null, 2);
        SubAgentProfileRegistry profiles = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 2);
        SubAgentProfile profile = profiles.create("Persistent worker", AgentMode.EXECUTION, null, "", 2,
                List.of(), List.of(), true);
        ContextManager contexts = new ContextManager(new InMemoryConversationStore(), 20, 10_000);
        ModelRegistry models = new ModelRegistry(new InMemoryModelProfileStore(), "http://localhost", "deepseek",
                "test-model", null, "", 0);
        SubAgentSessionManager manager = new SubAgentSessionManager(
                new InMemorySubAgentSessionStore(), contexts, new SubAgentRunner(loop, profiles), profiles, models, runs);
        try {
            SubAgentSession session = manager.create(profile.id());
            AgentRunHandle first = manager.send(session.id(), "remember this", null);
            assertEquals("answer-1", first.result().get().answer());
            AgentRunHandle second = manager.send(session.id(), "what did I ask you to remember?", null);
            assertEquals("answer-2", second.result().get().answer());

            assertTrue(requests.get(1).stream().anyMatch(message ->
                    message.role() == ChatMessage.Role.USER && "remember this".equals(message.content())));
            assertEquals(2, runs.list().size());
            assertTrue(runs.list().stream().allMatch(run -> run.status() == RunStatus.COMPLETED));
            assertEquals(SubAgentSessionStatus.OPEN, manager.find(session.id()).status());
        } finally {
            loop.close();
        }
    }

    @Test
    void closesSessionAndRejectsFurtherMessages() throws Exception {
        RunManager runs = new RunManager(new InMemoryRunStore());
        AgentLoop loop = new AgentLoop((messages, definitions) -> new ModelResponse("done", List.of(), "stop"),
                new ToolRegistry(), null, null, null, runs, null, null, 2);
        SubAgentProfileRegistry profiles = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 2);
        SubAgentProfile profile = profiles.create("Closable worker", AgentMode.EXECUTION, null, "", 2,
                List.of(), List.of(), true);
        ModelRegistry models = new ModelRegistry(new InMemoryModelProfileStore(), "http://localhost", "deepseek",
                "test-model", null, "", 0);
        SubAgentSessionManager manager = new SubAgentSessionManager(new InMemorySubAgentSessionStore(),
                new ContextManager(new InMemoryConversationStore(), 20), new SubAgentRunner(loop, profiles), profiles,
                models, runs);
        try {
            SubAgentSession session = manager.create(profile.id());
            assertEquals(SubAgentSessionStatus.CLOSED, manager.close(session.id()).status());
            assertThrows(IllegalStateException.class, () -> manager.send(session.id(), "too late", null));
        } finally {
            loop.close();
        }
    }
}
