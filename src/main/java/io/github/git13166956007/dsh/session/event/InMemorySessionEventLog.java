package io.github.git13166956007.dsh.session.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

public final class InMemorySessionEventLog implements SessionEventLog {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SessionEvent>> events =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<SessionEvent>>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<SessionEvent>>> subscribers =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<SessionEvent>>>();

    @Override
    public SessionEvent append(String sessionId, String type, JsonNode payload) {
        CopyOnWriteArrayList<SessionEvent> stream = events.computeIfAbsent(sessionId,
                ignored -> new CopyOnWriteArrayList<SessionEvent>());
        SessionEvent event;
        synchronized (stream) {
            event = new SessionEvent(UUID.randomUUID().toString(), sessionId, stream.size() + 1,
                    Instant.now(), type, payload.deepCopy());
            stream.add(event);
        }
        for (Consumer<SessionEvent> consumer : subscribers.getOrDefault(sessionId,
                new CopyOnWriteArrayList<Consumer<SessionEvent>>())) consumer.accept(event);
        return event;
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
