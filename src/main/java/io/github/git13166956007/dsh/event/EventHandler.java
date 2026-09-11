package io.github.git13166956007.dsh.event;

@FunctionalInterface
public interface EventHandler<T> {
    EventOutcome<T> handle(T event, EventNext<T> next) throws Exception;
}
