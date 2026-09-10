package io.github.git13166956007.dsh.service;

import java.util.Objects;

public final class ServiceKey<T> {
    private final String name;
    private final Class<T> type;

    public ServiceKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ServiceKey)) return false;
        ServiceKey<?> key = (ServiceKey<?>) other;
        return name.equals(key.name) && type.equals(key.type);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + type.hashCode();
    }

    @Override
    public String toString() {
        return name + "<" + type.getSimpleName() + ">";
    }
}
