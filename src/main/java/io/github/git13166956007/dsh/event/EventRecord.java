package io.github.git13166956007.dsh.event;

import java.time.Instant;
import java.util.UUID;

public record EventRecord(String id, String eventName, Object payload, Object value, boolean accepted,
                          String reason, String error, Instant occurredAt) {
    public EventRecord(String eventName, Object payload, Object value, boolean accepted,
                       String reason, String error, Instant occurredAt) {
        this(UUID.randomUUID().toString(), eventName, payload, value, accepted, reason, error, occurredAt);
    }

    public EventRecord {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("event id is required");
        if (eventName == null || eventName.isBlank()) throw new IllegalArgumentException("event name is required");
        if (payload == null) throw new IllegalArgumentException("event payload is required");
        if (occurredAt == null) occurredAt = Instant.now();
    }

    public boolean failed() {
        return error != null && !error.isBlank();
    }
}
