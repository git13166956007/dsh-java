package io.github.git13166956007.dsh.event;

import io.github.git13166956007.dsh.plugin.Registration;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Typed, cancellable, async-capable event bus with optional journaling. */
public final class EventBus implements AutoCloseable {
    private final Map<String, CopyOnWriteArrayList<Consumer<Object>>> legacyListeners =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<Object>>>();
    private final Map<EventKey<?>, CopyOnWriteArrayList<RegisteredHandler<?>>> handlers =
            new ConcurrentHashMap<EventKey<?>, CopyOnWriteArrayList<RegisteredHandler<?>>>();
    private final ExecutorService executor;
    private final EventJournal journal;
    private final ObjectMapper objectMapper;

    public EventBus() {
        this(null);
    }

    public EventBus(EventJournal journal) {
        this.journal = journal;
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "dsh-event-bus");
            thread.setDaemon(true);
            return thread;
        });
    }

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
        return on(key, EventHandlerOptions.defaults(), (event, next, ignored) -> handler.handle(event, next));
    }

    public <T> Registration on(EventKey<T> key, int priority, EventHandler<T> handler) {
        return on(key, new EventHandlerOptions(priority, null),
                (event, next, ignored) -> handler.handle(event, next));
    }

    public <T> Registration on(EventKey<T> key, EventHandlerOptions options,
                               ContextualEventHandler<T> handler) {
        if (key == null || options == null || handler == null) {
            throw new IllegalArgumentException("event key, options and handler are required");
        }
        CopyOnWriteArrayList<RegisteredHandler<?>> values = handlers.computeIfAbsent(
                key, ignored -> new CopyOnWriteArrayList<RegisteredHandler<?>>());
        RegisteredHandler<T> registered = new RegisteredHandler<T>(options, handler);
        values.add(registered);
        List<RegisteredHandler<?>> ordered = new ArrayList<RegisteredHandler<?>>(values);
        ordered.sort(Comparator.comparingInt((RegisteredHandler<?> value) -> value.options.priority()).reversed());
        values.clear();
        values.addAll(ordered);
        return () -> {
            values.remove(registered);
            if (values.isEmpty()) handlers.remove(key, values);
        };
    }

    public <T> EventOutcome<T> waterfall(EventKey<T> key, T event) throws Exception {
        return waterfall(key, event, new CancellationSource());
    }

    public <T> EventOutcome<T> waterfall(EventKey<T> key, T event, CancellationToken cancellation) throws Exception {
        if (key == null) throw new IllegalArgumentException("event key is required");
        if (cancellation == null) throw new IllegalArgumentException("cancellation token is required");
        key.payloadType().cast(event);
        try {
            EventOutcome<T> outcome = invoke(key, handlers.getOrDefault(key,
                    new CopyOnWriteArrayList<RegisteredHandler<?>>()), 0, event, cancellation);
            journal(new EventRecord(key.name(), event, outcome.value(), outcome.accepted(), outcome.reason(),
                    null, Instant.now()));
            return outcome;
        } catch (Exception exception) {
            journal(new EventRecord(key.name(), event, null, false, null, exception.toString(), Instant.now()));
            throw exception;
        }
    }

    public <T> T rewrite(EventKey<T> key, T event) throws Exception {
        EventOutcome<T> outcome = waterfall(key, event);
        if (!outcome.accepted()) throw new EventRejectedException(key, outcome.reason());
        return outcome.value();
    }

    public <T> CompletableFuture<EventOutcome<T>> waterfallAsync(EventKey<T> key, T event) {
        return waterfallAsync(key, event, new CancellationSource(), null);
    }

    public <T> CompletableFuture<EventOutcome<T>> waterfallAsync(EventKey<T> key, T event,
                                                                  CancellationSource cancellation,
                                                                  Duration timeout) {
        if (cancellation == null) throw new IllegalArgumentException("cancellation source is required");
        CompletableFuture<EventOutcome<T>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return waterfall(key, event, cancellation);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
        if (timeout != null) {
            if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
            future.orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .exceptionally(exception -> {
                        cancellation.cancel();
                        return null;
                    });
        }
        return future;
    }

    public EventOutcome<?> recover(EventRecord record, Map<String, ? extends EventKey<?>> keys) throws Exception {
        if (record == null || !record.failed()) throw new IllegalArgumentException("a failed event record is required");
        EventKey<?> key = keys == null ? null : keys.get(record.eventName());
        if (key == null) throw new IllegalArgumentException("event key is not registered: " + record.eventName());
        return recoverTyped(key, record.payload());
    }

    public void emit(String event, Object payload) {
        List<Consumer<Object>> values = legacyListeners.get(event);
        if (values == null) return;
        for (Consumer<Object> listener : values) listener.accept(payload);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private <T> EventOutcome<T> invoke(EventKey<T> key, List<RegisteredHandler<?>> values, int index,
                                       T event, CancellationToken cancellation) throws Exception {
        cancellation.throwIfCancelled();
        key.payloadType().cast(event);
        if (index >= values.size()) return EventOutcome.accept(event);
        @SuppressWarnings("unchecked") RegisteredHandler<T> handler = (RegisteredHandler<T>) values.get(index);
        EventContext context = new EventContext(cancellation, handler.options.timeout());
        EventNext<T> next = nextEvent -> invoke(key, values, index + 1, nextEvent, cancellation);
        if (handler.options.timeout() == null) return handler.handler.handle(event, next, context);
        CompletableFuture<EventOutcome<T>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return handler.handler.handle(event, next, context);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
        try {
            return future.get(handler.options.timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw exception;
        }
    }

    private <T> EventOutcome<?> recoverTyped(EventKey<T> key, Object payload) throws Exception {
        T typed;
        if (key.payloadType().isInstance(payload)) {
            typed = key.payloadType().cast(payload);
        } else if (payload instanceof JsonNode node) {
            typed = objectMapper.treeToValue(node, key.payloadType());
        } else {
            throw new IllegalArgumentException("event payload is not compatible with " + key.payloadType().getName());
        }
        return waterfall(key, typed, new CancellationSource());
    }

    private void journal(EventRecord record) throws Exception {
        if (journal != null) journal.append(record);
    }

    private record RegisteredHandler<T>(EventHandlerOptions options, ContextualEventHandler<T> handler) { }

    public static final class EventRejectedException extends IllegalStateException {
        public EventRejectedException(EventKey<?> key, String reason) {
            super("event rejected: " + key.name() + (reason == null ? "" : " (" + reason + ")"));
        }
    }
}
