package io.github.git13166956007.dsh.event;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class EventBusTest {
    private static final EventKey<String> TEXT = new EventKey<String>("test.text", String.class);

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
}
