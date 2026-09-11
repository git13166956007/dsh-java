package io.github.git13166956007.dsh.event;

import org.junit.jupiter.api.Test;

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
}
