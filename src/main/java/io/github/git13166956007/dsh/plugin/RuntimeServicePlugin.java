package io.github.git13166956007.dsh.plugin;

import io.github.git13166956007.dsh.service.ServiceKey;
import java.util.Set;

/** Adapts an existing implementation into a replaceable Runtime service plugin. */
public final class RuntimeServicePlugin<T> implements DshPlugin {
    private final String id;
    private final ServiceKey<T> key;
    private final T service;
    private final Set<String> dependencies;

    public RuntimeServicePlugin(String id, ServiceKey<T> key, T service, String... dependencies) {
        this.id = id;
        this.key = key;
        this.service = service;
        this.dependencies = dependencies == null ? Set.of() : Set.of(dependencies);
    }

    @Override
    public String id() { return id; }

    @Override
    public Set<String> dependencies() { return dependencies; }

    @Override
    public void start(PluginContext context) {
        context.provide(key, service);
        if (service instanceof AutoCloseable closeable) context.effect(closeable);
    }
}
