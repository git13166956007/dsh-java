package io.github.git13166956007.dsh.session.event;

import java.util.List;

/** A bounded session-event page and the sequence to use for the next read. */
public record SessionEventPage(List<SessionEvent> events, long nextSequence, boolean hasMore) {
    public SessionEventPage {
        events = events == null ? List.of() : List.copyOf(events);
        if (nextSequence < 0) throw new IllegalArgumentException("session event sequence must not be negative");
    }
}
