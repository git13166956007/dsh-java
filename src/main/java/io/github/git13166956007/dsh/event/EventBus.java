package io.github.git13166956007.dsh.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import io.github.git13166956007.dsh.plugin.Registration;

public final class EventBus {
    private final Map<String, CopyOnWriteArrayList<Consumer<Object>>> listeners =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<Object>>>();

    public Registration on(String event, Consumer<Object> listener) {
        CopyOnWriteArrayList<Consumer<Object>> handlers = listeners.computeIfAbsent(
                event, ignored -> new CopyOnWriteArrayList<Consumer<Object>>());
        handlers.add(listener);
        return () -> {
            handlers.remove(listener);
            if (handlers.isEmpty()) listeners.remove(event, handlers);
        };
    }

    public void emit(String event, Object payload) {
        List<Consumer<Object>> handlers = listeners.get(event);
        if (handlers == null) return;
        for (Consumer<Object> handler : handlers) handler.accept(payload);
    }
}
