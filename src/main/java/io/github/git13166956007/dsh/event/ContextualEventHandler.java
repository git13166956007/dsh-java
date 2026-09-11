package io.github.git13166956007.dsh.event;

@FunctionalInterface
public interface ContextualEventHandler<T> {
    EventOutcome<T> handle(T event, EventNext<T> next, EventContext context) throws Exception;
}
