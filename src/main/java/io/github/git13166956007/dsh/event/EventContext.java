package io.github.git13166956007.dsh.event;

import java.time.Duration;

public final class EventContext {
    private final CancellationToken cancellation;
    private final long deadlineNanos;

    EventContext(CancellationToken cancellation, Duration timeout) {
        this.cancellation = cancellation;
        this.deadlineNanos = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
    }

    public CancellationToken cancellation() {
        return cancellation;
    }

    public boolean isCancelled() {
        return cancellation.isCancelled() || System.nanoTime() >= deadlineNanos;
    }

    public void throwIfCancelled() {
        if (isCancelled()) throw new EventCancelledException();
    }
}
