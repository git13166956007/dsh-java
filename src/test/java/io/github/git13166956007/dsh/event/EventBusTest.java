package io.github.git13166956007.dsh.event;

import io.github.git13166956007.dsh.plugin.Registration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class EventBusTest {
    private static final EventKey<String> TEXT = new EventKey<String>("test.text", String.class);
    private static final EventKey<Payload> PAYLOAD = new EventKey<Payload>("test.payload", Payload.class);
    private record Payload(String value) { }

    @Test
    void handlersRewriteInWaterfallOrder() throws Exception {
        EventBus bus = new EventBus();
        bus.on(TEXT, (value, next) -> next.proceed(value + "-first"));
        bus.on(TEXT, (value, next) -> EventOutcome.accept(value + "-second"));
        assertEquals("start-first-second", bus.rewrite(TEXT, "start"));
    }

    @Test
    void handlerCanRejectTypedEvent() {
        EventBus bus = new EventBus();
        bus.on(TEXT, (value, next) -> EventOutcome.reject("blocked"));
        assertThrows(EventBus.EventRejectedException.class, () -> bus.rewrite(TEXT, "start"));
    }

    @Test
    void handlersRunByPriorityAndJournalSuccessfulEvents() throws Exception {
        InMemoryEventJournal journal = new InMemoryEventJournal();
        EventBus bus = new EventBus(journal);
        bus.on(TEXT, -10, (value, next) -> next.proceed(value + "-low"));
        bus.on(TEXT, 10, (value, next) -> next.proceed(value + "-high"));

        assertEquals("start-high-low", bus.rewrite(TEXT, "start"));
        assertEquals(1, journal.read().size());
        assertEquals(false, journal.read().get(0).failed());
        bus.close();
    }

    @Test
    void supportsCancellationAsyncDispatchAndFailureRecovery() throws Exception {
        EventBus bus = new EventBus();
        CancellationSource source = new CancellationSource();
        source.cancel();
        assertThrows(EventCancelledException.class, () -> bus.waterfall(TEXT, "cancelled", source));

        assertEquals("async", bus.waterfallAsync(TEXT, "async").get(2, TimeUnit.SECONDS).value());

        InMemoryEventJournal journal = new InMemoryEventJournal();
        EventBus failing = new EventBus(journal);
        failing.on(TEXT, (value, next) -> { throw new IllegalStateException("fixture"); });
        assertThrows(IllegalStateException.class, () -> failing.waterfall(TEXT, "retry"));
        EventRecord failed = journal.read().get(0);
        assertEquals(true, failed.failed());

        EventBus recovered = new EventBus();
        assertEquals("retry", recovered.recover(failed, Map.of(TEXT.name(), TEXT)).value());
        EventRecord persisted = new EventRecord(TEXT.name(), JsonNodeFactory.instance.textNode("persisted"),
                null, false, null, "fixture", java.time.Instant.now());
        assertEquals("persisted", recovered.recover(persisted, Map.of(TEXT.name(), TEXT)).value());
        EventRecord typed = new EventRecord(PAYLOAD.name(), JsonNodeFactory.instance.objectNode().put("value", "typed"),
                null, false, null, "fixture", java.time.Instant.now());
        EventOutcome<?> typedOutcome = recovered.recover(typed, Map.of(PAYLOAD.name(), PAYLOAD));
        assertEquals("typed", ((Payload) typedOutcome.value()).value());
        bus.close();
        failing.close();
        recovered.close();
    }

    @Test
    void handlerTimeoutFailsDispatch() {
        EventBus bus = new EventBus();
        bus.on(TEXT, new EventHandlerOptions(0, Duration.ofMillis(10)), (value, next, context) -> {
            Thread.sleep(100);
            return next.proceed(value);
        });
        assertThrows(TimeoutException.class, () -> bus.waterfall(TEXT, "slow"));
        bus.close();
    }

    @Test
    void redactsCredentialsBeforeJournaling() throws Exception {
        InMemoryEventJournal journal = new InMemoryEventJournal();
        EventBus bus = new EventBus(journal);
        EventKey<CredentialPayload> key = new EventKey<>("test.credentials", CredentialPayload.class);

        bus.waterfall(key, new CredentialPayload("sk-secret", "Bearer secret", "visible"));

        EventRecord record = journal.read().get(0);
        String payload = record.payload().toString();
        assertTrue(payload.contains("[REDACTED]"));
        assertTrue(!payload.contains("sk-secret"));
        assertTrue(!payload.contains("Bearer secret"));
        assertTrue(payload.contains("visible"));
        bus.close();
    }

    @Test
    void concurrentHandlerRegistrationDoesNotLoseHandlers() throws Exception {
        EventBus bus = new EventBus();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        AtomicInteger calls = new AtomicInteger();
        try {
            java.util.List<Future<Registration>> registrations = new java.util.ArrayList<>();
            for (int index = 0; index < 128; index++) {
                registrations.add(executor.submit(() -> bus.on(TEXT, (value, next) -> {
                    calls.incrementAndGet();
                    return next.proceed(value);
                })));
            }
            for (Future<Registration> registration : registrations) registration.get();
            assertEquals("start", bus.rewrite(TEXT, "start"));
            assertEquals(128, calls.get());
        } finally {
            executor.shutdownNow();
            bus.close();
        }
    }

    @Test
    void retriesJournalWithTheSameEventIdAndDeduplicatesAppend() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        InMemoryEventJournal stored = new InMemoryEventJournal();
        EventJournal journal = new EventJournal() {
            @Override
            public void append(EventRecord record) {
                if (attempts.getAndIncrement() == 0) throw new IllegalStateException("temporary journal failure");
                stored.append(record);
            }

            @Override
            public java.util.List<EventRecord> read() {
                return stored.read();
            }
        };
        EventBus bus = new EventBus(journal);
        assertEquals("retry", bus.rewrite(TEXT, "retry"));
        EventRecord record = journal.read().get(0);
        stored.append(record);
        assertEquals(1, journal.read().size());
        assertEquals(2, attempts.get());
        bus.close();
    }

    private record CredentialPayload(String apiKey, String authorization, String value) { }
}
