package io.github.git13166956007.dsh.event;

public interface CancellationToken {
    boolean isCancelled();

    default void throwIfCancelled() {
        if (isCancelled()) throw new EventCancelledException();
    }
}
