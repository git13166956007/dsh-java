package io.github.git13166956007.dsh.event;

import java.time.Instant;

public record EventRecord(String eventName, Object payload, Object value, boolean accepted,
                          String reason, String error, Instant occurredAt) {
    public EventRecord {
        if (eventName == null || eventName.isBlank()) throw new IllegalArgumentException("event name is required");
        if (payload == null) throw new IllegalArgumentException("event payload is required");
        if (occurredAt == null) occurredAt = Instant.now();
    }

    public boolean failed() {
        return error != null && !error.isBlank();
    }
}
