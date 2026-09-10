package io.github.git13166956007.dsh.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
