package io.github.git13166956007.dsh.session.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

public final class InMemorySessionEventLog implements SessionEventLog {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SessionEvent>> events =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<SessionEvent>>();
    private final ConcurrentHashMap<String, SessionEvent> byId = new ConcurrentHashMap<String, SessionEvent>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<SessionEvent>>> subscribers =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<SessionEvent>>>();

    @Override
    public SessionEvent append(String sessionId, String type, JsonNode payload) {
        return append(sessionId, java.util.UUID.randomUUID().toString(), type, payload);
    }

    @Override
    public SessionEvent append(String sessionId, String eventId, String type, JsonNode payload) {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("event ID must not be blank");
        CopyOnWriteArrayList<SessionEvent> stream = events.computeIfAbsent(sessionId,
                ignored -> new CopyOnWriteArrayList<SessionEvent>());
        SessionEvent event;
        boolean appended = false;
        synchronized (byId) {
            event = byId.get(eventId);
            if (event != null) {
                verifyIdempotent(event, sessionId, type, payload);
            } else {
                synchronized (stream) {
                    event = new SessionEvent(eventId, sessionId, stream.size() + 1,
                            Instant.now(), type, payload);
                    stream.add(event);
                    byId.put(eventId, event);
                    appended = true;
                }
            }
        }
        if (!appended) return event;
        for (Consumer<SessionEvent> consumer : subscribers.getOrDefault(sessionId,
                new CopyOnWriteArrayList<Consumer<SessionEvent>>())) {
            try {
                consumer.accept(event);
            } catch (Exception ignored) {
                // Subscribers are observers; a broken observer must not turn a committed append into a failure.
            }
        }
        return event;
    }

    private static void verifyIdempotent(SessionEvent existing, String sessionId, String type, JsonNode payload) {
        if (!existing.sessionId().equals(sessionId) || !existing.type().equals(type)
                || !existing.payload().equals(payload)) {
            throw new IllegalArgumentException("event ID was already used with different content: " + existing.id());
        }
    }

    @Override
    public List<SessionEvent> read(String sessionId) {
        return List.copyOf(new ArrayList<SessionEvent>(events.getOrDefault(sessionId,
                new CopyOnWriteArrayList<SessionEvent>())));
    }

    @Override
    public Registration subscribe(String sessionId, Consumer<SessionEvent> consumer) {
        CopyOnWriteArrayList<Consumer<SessionEvent>> values = subscribers.computeIfAbsent(sessionId,
                ignored -> new CopyOnWriteArrayList<Consumer<SessionEvent>>());
        values.add(consumer);
        return () -> {
            values.remove(consumer);
            if (values.isEmpty()) subscribers.remove(sessionId, values);
        };
    }
}
