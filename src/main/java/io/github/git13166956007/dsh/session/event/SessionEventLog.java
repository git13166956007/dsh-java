package io.github.git13166956007.dsh.session.event;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

public interface SessionEventLog {
    default SessionEvent append(String sessionId, String type, JsonNode payload) throws Exception {
        return append(sessionId, UUID.randomUUID().toString(), type, payload);
    }

    /** Appends once for an event ID; retries with the same ID return the original event. */
    SessionEvent append(String sessionId, String eventId, String type, JsonNode payload) throws Exception;

    List<SessionEvent> read(String sessionId) throws Exception;

    /** Reads a bounded page after a session sequence number. */
    default SessionEventPage read(String sessionId, long afterSequence, int limit) throws Exception {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("session ID is required");
        if (afterSequence < 0) throw new IllegalArgumentException("session event sequence must not be negative");
        if (limit <= 0) throw new IllegalArgumentException("session event page size must be positive");
        List<SessionEvent> all = read(sessionId);
        int from = 0;
        while (from < all.size() && all.get(from).sequence() <= afterSequence) from++;
        int to = Math.min(all.size(), from + limit);
        long next = to == from ? afterSequence : all.get(to - 1).sequence();
        return new SessionEventPage(all.subList(from, to), next, to < all.size());
    }

    default Registration subscribe(String sessionId, Consumer<SessionEvent> consumer) {
        return Registration.NOOP;
    }

    @FunctionalInterface
    interface Registration extends AutoCloseable {
        Registration NOOP = () -> { };
        @Override
        void close();
    }
}
