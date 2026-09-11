package io.github.git13166956007.dsh.event;

import io.github.git13166956007.dsh.plugin.Registration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Typed event bus with legacy string broadcast compatibility. */
public final class EventBus {
    private final Map<String, CopyOnWriteArrayList<Consumer<Object>>> legacyListeners =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<Object>>>();
    private final Map<EventKey<?>, CopyOnWriteArrayList<EventHandler<?>>> handlers =
            new ConcurrentHashMap<EventKey<?>, CopyOnWriteArrayList<EventHandler<?>>>();

    public Registration on(String event, Consumer<Object> listener) {
        CopyOnWriteArrayList<Consumer<Object>> values = legacyListeners.computeIfAbsent(
                event, ignored -> new CopyOnWriteArrayList<Consumer<Object>>());
        values.add(listener);
        return () -> {
            values.remove(listener);
            if (values.isEmpty()) legacyListeners.remove(event, values);
        };
    }

    public <T> Registration on(EventKey<T> key, EventHandler<T> handler) {
        if (key == null || handler == null) throw new IllegalArgumentException("event key and handler are required");
        CopyOnWriteArrayList<EventHandler<?>> values = handlers.computeIfAbsent(
                key, ignored -> new CopyOnWriteArrayList<EventHandler<?>>());
        values.add(handler);
        return () -> {
            values.remove(handler);
            if (values.isEmpty()) handlers.remove(key, values);
        };
    }

    public <T> EventOutcome<T> waterfall(EventKey<T> key, T event) throws Exception {
        if (key == null) throw new IllegalArgumentException("event key is required");
        key.payloadType().cast(event);
        List<EventHandler<?>> values = handlers.getOrDefault(key,
                new CopyOnWriteArrayList<EventHandler<?>>());
        return invoke(key, values, 0, event);
    }

    public <T> T rewrite(EventKey<T> key, T event) throws Exception {
        EventOutcome<T> outcome = waterfall(key, event);
        if (!outcome.accepted()) throw new EventRejectedException(key, outcome.reason());
        return outcome.value();
    }

    public void emit(String event, Object payload) {
        List<Consumer<Object>> values = legacyListeners.get(event);
        if (values == null) return;
        for (Consumer<Object> listener : values) listener.accept(payload);
    }

    private <T> EventOutcome<T> invoke(EventKey<T> key, List<EventHandler<?>> values, int index, T event)
            throws Exception {
        key.payloadType().cast(event);
        if (index >= values.size()) return EventOutcome.accept(event);
        @SuppressWarnings("unchecked") EventHandler<T> handler = (EventHandler<T>) values.get(index);
        return handler.handle(event, next -> invoke(key, values, index + 1, next));
    }

    public static final class EventRejectedException extends IllegalStateException {
        public EventRejectedException(EventKey<?> key, String reason) {
            super("event rejected: " + key.name() + (reason == null ? "" : " (" + reason + ")"));
        }
    }
}
