package io.github.git13166956007.dsh.core.scope;

import io.github.git13166956007.dsh.core.profile.RuntimeProfile;
import io.github.git13166956007.dsh.core.profile.ProfilePatch;
import io.github.git13166956007.dsh.plugin.Registration;
import io.github.git13166956007.dsh.service.ServiceKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A hierarchical runtime scope for per-agent and per-session services. */
public final class Scope implements AutoCloseable {
    private final String id;
    private final Scope parent;
    private final Map<ServiceKey<?>, Object> services = new HashMap<ServiceKey<?>, Object>();
    private final Deque<AutoCloseable> effects = new ArrayDeque<AutoCloseable>();
    private final List<Scope> children = new ArrayList<Scope>();
    private RuntimeProfile profile;
    private boolean closed;

    public Scope(String id) {
        this(id, null, RuntimeProfile.empty(id));
    }

    private Scope(String id, Scope parent, RuntimeProfile profile) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("scope id must not be blank");
        this.id = id;
        this.parent = parent;
        this.profile = profile;
    }

    public String id() { return id; }

    public Scope parent() { return parent; }

    public <T> T resolve(ServiceKey<T> key) {
        Scope current = this;
        while (current != null) {
            Object value;
            Scope next;
            synchronized (current) {
                value = current.services.get(key);
                next = current.parent;
            }
            if (value != null) return key.type().cast(value);
            current = next;
        }
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
        RuntimeProfile inherited = profile();
        RuntimeProfile childProfile = inherited == null ? null : new RuntimeProfile(childId, inherited.id(),
                inherited.modelId(), inherited.systemPrompt(), inherited.allowedToolNames(),
                inherited.allowedSkillIds(), inherited.permissions());
        Scope child = new Scope(childId, this, childProfile);
        children.add(child);
        return child;
    }

    public synchronized Scope child(String childId, ProfilePatch patch) {
        Scope child = child(childId);
        if (patch != null) child.withProfile(patch.apply(profile(), childId));
        return child;
    }

    public RuntimeProfile profile() {
        Scope current = this;
        while (current != null) {
            RuntimeProfile value;
            Scope next;
            synchronized (current) {
                value = current.profile;
                next = current.parent;
            }
            if (value != null) return value;
            current = next;
        }
        return null;
    }

    public synchronized Scope withProfile(RuntimeProfile nextProfile) {
        ensureOpen();
        this.profile = nextProfile;
        return this;
    }

    @Override
    public void close() {
        List<Scope> childScopes;
        List<AutoCloseable> ownedEffects;
        synchronized (this) {
            if (closed) return;
            closed = true;
            childScopes = new ArrayList<Scope>(children);
            children.clear();
            ownedEffects = new ArrayList<AutoCloseable>(effects);
            effects.clear();
            services.clear();
        }
        for (int index = childScopes.size() - 1; index >= 0; index--) childScopes.get(index).close();
        for (AutoCloseable effect : ownedEffects) {
            try { effect.close(); } catch (Exception ignored) { }
        }
        if (parent != null) {
            synchronized (parent) { parent.children.remove(this); }
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("scope is closed: " + id);
    }
}
