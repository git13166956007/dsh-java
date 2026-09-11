package io.github.git13166956007.dsh.event;

import java.time.Duration;

public record EventHandlerOptions(int priority, Duration timeout) {
    public EventHandlerOptions {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("event handler timeout must be positive");
        }
    }

    public static EventHandlerOptions defaults() {
        return new EventHandlerOptions(0, null);
    }
}
