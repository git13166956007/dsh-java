package io.github.git13166956007.dsh.session.event;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class SessionEventProjectionTest {
    @Test
    void projectsMessagesFromAppendOnlyEvents() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InMemorySessionEventLog log = new InMemorySessionEventLog();
        log.append("session-1", SessionEventTypes.CREATED, mapper.createObjectNode());
        log.append("session-1", SessionEventTypes.USER_MESSAGE,
                SessionEventCodec.message(mapper, ChatMessage.user("hello")));
        log.append("session-1", SessionEventTypes.ASSISTANT_MESSAGE,
                SessionEventCodec.message(mapper, ChatMessage.assistant("world", List.of(), null)));

        assertEquals(List.of("hello", "world"),
                SessionEventProjection.messages(log.read("session-1")).stream()
                        .map(ChatMessage::content).toList());
    }

    @Test
    void deletionEventRemovesMessagesFromProjection() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InMemorySessionEventLog log = new InMemorySessionEventLog();
        log.append("session-1", SessionEventTypes.USER_MESSAGE,
                SessionEventCodec.message(mapper, ChatMessage.user("old")));
        log.append("session-1", SessionEventTypes.DELETED, mapper.createObjectNode());
        assertEquals(List.of(), SessionEventProjection.messages(log.read("session-1")));
    }
}
