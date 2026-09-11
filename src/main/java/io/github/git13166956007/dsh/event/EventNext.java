package io.github.git13166956007.dsh.event;

@FunctionalInterface
public interface EventNext<T> {
    EventOutcome<T> proceed(T event) throws Exception;
}
