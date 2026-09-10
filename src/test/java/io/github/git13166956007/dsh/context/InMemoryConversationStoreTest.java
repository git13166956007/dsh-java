package io.github.git13166956007.dsh.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryConversationStoreTest {
    @Test
    void keepsOnlyTheNewestHistoryWindow() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        String id = store.open(null, "test");
        store.append(id, ChatMessage.user("one"));
        store.append(id, ChatMessage.assistant("two", List.of()));
        store.append(id, ChatMessage.user("three"));

        assertEquals(List.of("two", "three"),
                store.load(id, 2).stream().map(ChatMessage::content).toList());
    }

    @Test
    void contextWindowKeepsNewestMessagesWithinTokenBudget() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        ContextManager context = new ContextManager(store, 10, 4);
        String id = context.open(null, "budget");
        context.append(id, ChatMessage.user("1234567890"));
        context.append(id, ChatMessage.assistant("old", List.of()));
        context.append(id, ChatMessage.user("new"));

        ContextWindow window = context.window(id);
        assertEquals(2, window.messages().size());
        assertEquals("old", window.messages().get(0).content());
        assertEquals("new", window.messages().get(1).content());
        assertTrue(window.truncated());
        assertTrue(window.estimatedTokens() <= 4);
    }
}
