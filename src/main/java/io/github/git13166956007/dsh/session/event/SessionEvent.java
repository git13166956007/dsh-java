package io.github.git13166956007.dsh.session.event;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

public record SessionEvent(
        String id,
        String sessionId,
        long sequence,
        Instant occurredAt,
        String type,
        JsonNode payload) {
    public SessionEvent {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("session event id must not be blank");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("session id must not be blank");
        if (sequence < 1) throw new IllegalArgumentException("session event sequence must be positive");
        if (occurredAt == null) occurredAt = Instant.now();
        if (type == null || type.isBlank()) throw new IllegalArgumentException("session event type must not be blank");
        if (payload == null) throw new IllegalArgumentException("session event payload must not be null");
    }
}
