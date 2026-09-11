package io.github.git13166956007.dsh.session.event;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable event IDs for transport retries and deterministic projections. */
public final class SessionEventIds {
    private SessionEventIds() { }

    public static String deterministic(String namespace, String key) {
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("event namespace is required");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("event key is required");
        return UUID.nameUUIDFromBytes((namespace.trim() + ":" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
