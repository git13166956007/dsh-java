package io.github.git13166956007.dsh.event;

import java.util.Objects;

public record EventKey<T>(String name, Class<T> payloadType) {
    public EventKey {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("event name must not be blank");
        Objects.requireNonNull(payloadType, "payloadType");
    }

    @Override
    public String toString() { return name + "<" + payloadType.getSimpleName() + ">"; }
}
