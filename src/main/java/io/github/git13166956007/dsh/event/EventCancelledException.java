package io.github.git13166956007.dsh.event;

public final class EventCancelledException extends RuntimeException {
    public EventCancelledException() {
        super("event dispatch cancelled");
    }
}
