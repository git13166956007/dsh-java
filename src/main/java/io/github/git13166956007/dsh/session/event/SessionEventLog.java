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
