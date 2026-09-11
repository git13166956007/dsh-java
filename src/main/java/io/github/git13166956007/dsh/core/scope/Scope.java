package io.github.git13166956007.dsh.core.scope;

import io.github.git13166956007.dsh.core.profile.RuntimeProfile;
import io.github.git13166956007.dsh.core.profile.ProfilePatch;
import io.github.git13166956007.dsh.plugin.Registration;
import io.github.git13166956007.dsh.service.ServiceKey;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** A hierarchical runtime scope for per-agent and per-session services. */
public final class Scope implements AutoCloseable {
    private final String id;
    private final Scope parent;
    private final Map<ServiceKey<?>, Object> services = new HashMap<ServiceKey<?>, Object>();
    private final Deque<AutoCloseable> effects = new ArrayDeque<AutoCloseable>();
    private RuntimeProfile profile;
    private boolean closed;

    public Scope(String id) {
        this(id, null, null);
    }

    private Scope(String id, Scope parent, RuntimeProfile profile) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("scope id must not be blank");
        this.id = id;
        this.parent = parent;
        this.profile = profile;
    }

    public String id() { return id; }

    public Scope parent() { return parent; }

    public synchronized <T> T resolve(ServiceKey<T> key) {
        Object value = services.get(key);
        if (value != null) return key.type().cast(value);
        if (parent != null) return parent.resolve(key);
        throw new IllegalStateException("missing service in scope " + id + ": " + key);
    }

    public synchronized <T> T local(ServiceKey<T> key) {
        Object value = services.get(key);
        return value == null ? null : key.type().cast(value);
    }

    public synchronized <T> Registration provide(ServiceKey<T> key, T service) {
        ensureOpen();
        if (services.containsKey(key)) throw new IllegalStateException("duplicate service: " + key);
        services.put(key, service);
        Registration registration = () -> {
            synchronized (Scope.this) { services.remove(key, service); }
        };
        effect(registration);
        return registration;
    }

    public synchronized Registration effect(AutoCloseable closeable) {
        ensureOpen();
        if (closeable == null) throw new IllegalArgumentException("scope effect must not be null");
        effects.push(closeable);
        return () -> { synchronized (Scope.this) { effects.remove(closeable); } };
    }

    public synchronized Scope child(String childId) {
        ensureOpen();
        return new Scope(childId, this, profile);
    }

    public synchronized Scope child(String childId, ProfilePatch patch) {
        Scope child = child(childId);
        if (patch != null) child.withProfile(patch.apply(child.profile(), childId));
        return child;
    }

    public synchronized RuntimeProfile profile() {
        if (profile != null) return profile;
        return parent == null ? null : parent.profile();
    }

    public synchronized Scope withProfile(RuntimeProfile nextProfile) {
        ensureOpen();
        this.profile = nextProfile;
        return this;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        while (!effects.isEmpty()) {
            try { effects.pop().close(); } catch (Exception ignored) { }
        }
        services.clear();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("scope is closed: " + id);
    }
}
