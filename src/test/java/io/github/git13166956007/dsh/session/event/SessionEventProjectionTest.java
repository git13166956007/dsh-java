package io.github.git13166956007.dsh.session.event;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.context.ConversationSummary;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void concurrentInMemoryAppendsKeepAContiguousSequence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InMemorySessionEventLog log = new InMemorySessionEventLog();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (int index = 0; index < 200; index++) {
                int eventIndex = index;
                executor.submit(() -> log.append("session-concurrent", SessionEventTypes.USER_MESSAGE,
                        mapper.createObjectNode().put("index", eventIndex)));
            }
        } finally {
            executor.shutdown();
            assertEquals(true, executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        List<SessionEvent> events = log.read("session-concurrent");
        assertEquals(200, events.size());
        assertEquals(java.util.stream.LongStream.rangeClosed(1, 200).boxed().toList(),
                events.stream().map(SessionEvent::sequence).toList());
    }

    @Test
    void subscriberFailureDoesNotRejectAnAlreadyAppendedEvent() {
        InMemorySessionEventLog log = new InMemorySessionEventLog();
        AtomicBoolean called = new AtomicBoolean();
        log.subscribe("observer", event -> {
            called.set(true);
            throw new IllegalStateException("observer failure");
        });

        log.append("observer", SessionEventTypes.USER_MESSAGE,
                SessionEventCodec.message(new ObjectMapper(), ChatMessage.user("persisted")));

        assertEquals(true, called.get());
        assertEquals(List.of("persisted"), SessionEventProjection.messages(log.read("observer")).stream()
                .map(ChatMessage::content).toList());
    }

    @Test
    void appendIsIdempotentAndPayloadIsImmutable() throws Exception {
        InMemorySessionEventLog log = new InMemorySessionEventLog();
        AtomicInteger notifications = new AtomicInteger();
        log.subscribe("idempotent", event -> notifications.incrementAndGet());
        ObjectMapper mapper = new ObjectMapper();
        var payload = mapper.createObjectNode().put("value", "stable");

        SessionEvent first = log.append("idempotent", "event-1", SessionEventTypes.USER_MESSAGE, payload);
        payload.put("value", "mutated-after-append");
        assertEquals("stable", first.payload().path("value").asString());
        SessionEvent retry = log.append("idempotent", "event-1", SessionEventTypes.USER_MESSAGE,
                mapper.createObjectNode().put("value", "stable"));

        assertEquals(first, retry);
        assertEquals(1, log.read("idempotent").size());
        assertEquals(1, notifications.get());
        assertThrows(IllegalArgumentException.class, () -> log.append("idempotent", "event-1",
                SessionEventTypes.USER_MESSAGE, mapper.createObjectNode().put("value", "different")));
        assertThrows(IllegalArgumentException.class, () -> log.append("other-session", "event-1",
                SessionEventTypes.USER_MESSAGE, mapper.createObjectNode().put("value", "stable")));
    }

    @Test
    void summaryProjectionDoesNotRegressWhenOlderCompactionFinishesLater() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InMemorySessionEventLog log = new InMemorySessionEventLog();
        log.append("summary-race", SessionEventTypes.CREATED, mapper.createObjectNode());
        log.append("summary-race", SessionEventTypes.SUMMARY_UPDATED,
                mapper.createObjectNode().put("content", "newer").put("coveredMessageCount", 10));
        log.append("summary-race", SessionEventTypes.SUMMARY_UPDATED,
                mapper.createObjectNode().put("content", "older").put("coveredMessageCount", 4));

        ConversationSummary summary = SessionEventProjection.project(log.read("summary-race")).summary();
        assertEquals("newer", summary.content());
        assertEquals(10, summary.coveredMessageCount());
    }
}
