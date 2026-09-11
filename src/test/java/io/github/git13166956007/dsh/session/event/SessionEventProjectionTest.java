package io.github.git13166956007.dsh.session.event;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void projectsMetadataAndRollingSummaryInEventOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InMemorySessionEventLog log = new InMemorySessionEventLog();
        log.append("session-1", SessionEventTypes.CREATED, mapper.createObjectNode().put("title", "First"));
        log.append("session-1", SessionEventTypes.USER_MESSAGE,
                SessionEventCodec.message(mapper, ChatMessage.user("remember this")));
        log.append("session-1", SessionEventTypes.RENAMED, mapper.createObjectNode().put("title", "Renamed"));
        log.append("session-1", SessionEventTypes.SUMMARY_UPDATED,
                mapper.createObjectNode().put("content", "A concise summary").put("coveredMessageCount", 1));

        SessionEventProjection.Snapshot snapshot = SessionEventProjection.project(log.read("session-1"));
        assertEquals("Renamed", snapshot.title());
        assertEquals(1, snapshot.messages().size());
        assertEquals("A concise summary", snapshot.summary().content());
        assertEquals(1, snapshot.summary().coveredMessageCount());
        assertTrue(snapshot.createdAt().isBefore(snapshot.updatedAt())
                || snapshot.createdAt().equals(snapshot.updatedAt()));
    }

    @Test
    void recreatingAfterDeletionStartsANewProjection() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InMemorySessionEventLog log = new InMemorySessionEventLog();
        log.append("session-1", SessionEventTypes.CREATED, mapper.createObjectNode().put("title", "Old"));
        log.append("session-1", SessionEventTypes.USER_MESSAGE,
                SessionEventCodec.message(mapper, ChatMessage.user("old")));
        log.append("session-1", SessionEventTypes.DELETED, mapper.createObjectNode());
        log.append("session-1", SessionEventTypes.CREATED, mapper.createObjectNode().put("title", "New"));
        log.append("session-1", SessionEventTypes.USER_MESSAGE,
                SessionEventCodec.message(mapper, ChatMessage.user("new")));

        SessionEventProjection.Snapshot snapshot = SessionEventProjection.project(log.read("session-1"));
        assertEquals("New", snapshot.title());
        assertEquals(List.of("new"), snapshot.messages().stream().map(ChatMessage::content).toList());
        assertEquals(false, snapshot.deleted());
    }
}
