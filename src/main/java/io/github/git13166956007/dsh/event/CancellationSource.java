package io.github.git13166956007.dsh.event;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationSource implements CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }
}
