package io.github.git13166956007.dsh;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

public final class DshRuntime implements AutoCloseable {
    private final Map<ServiceKey<?>, Object> services = new HashMap<ServiceKey<?>, Object>();
    private final EventBus eventBus = new EventBus();
    private final Deque<AutoCloseable> effects = new ArrayDeque<AutoCloseable>();
    private final List<DshPlugin> plugins = new ArrayList<DshPlugin>();
    private boolean started;

    public static DshRuntime load(ClassLoader loader) throws Exception {
        DshRuntime runtime = new DshRuntime();
        for (DshPlugin plugin : ServiceLoader.load(DshPlugin.class, loader)) runtime.install(plugin);
        return runtime;
    }

    public void install(DshPlugin plugin) throws Exception {
        if (started) throw new IllegalStateException("runtime already started");
        plugin.start(new Context(plugin.id()));
        plugins.add(plugin);
    }

    public void start() {
        started = true;
        eventBus.emit("runtime.started", this);
    }

    public EventBus events() {
        return eventBus;
    }

    public <T> T service(ServiceKey<T> key) {
        Object value = services.get(key);
        if (value == null) throw new IllegalStateException("missing service: " + key);
        return key.type().cast(value);
    }

    @Override
    public void close() {
        while (!effects.isEmpty()) {
            try {
                effects.pop().close();
            } catch (Exception ignored) {
                // Best effort cleanup; one plugin must not block the rest.
            }
        }
        plugins.clear();
        services.clear();
        started = false;
    }

    private final class Context implements PluginContext {
        private final String pluginId;

        private Context(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override
        public <T> T service(ServiceKey<T> key) {
            Object value = services.get(key);
            if (value == null) throw new IllegalStateException(pluginId + " requires " + key);
            return key.type().cast(value);
        }

        @Override
        public <T> Registration provide(ServiceKey<T> key, T service) {
            if (services.containsKey(key)) throw new IllegalStateException("duplicate service: " + key);
            services.put(key, service);
            Registration registration = () -> services.remove(key, service);
            effect(registration);
            return registration;
        }

        @Override
        public Registration on(String event, java.util.function.Consumer<Object> listener) {
            Registration registration = eventBus.on(event, listener);
            effect(registration);
            return registration;
        }

        @Override
        public void effect(AutoCloseable closeable) {
            effects.push(closeable);
        }
    }
}
