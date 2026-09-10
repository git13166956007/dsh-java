package io.github.git13166956007.dsh.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import io.github.git13166956007.dsh.event.EventBus;
import io.github.git13166956007.dsh.plugin.DshPlugin;
import io.github.git13166956007.dsh.plugin.PluginContext;
import io.github.git13166956007.dsh.plugin.Registration;
import io.github.git13166956007.dsh.service.ServiceKey;

public final class DshRuntime implements AutoCloseable {
    private final Map<ServiceKey<?>, Object> services = new HashMap<ServiceKey<?>, Object>();
    private final EventBus eventBus = new EventBus();
    private final Deque<AutoCloseable> effects = new ArrayDeque<AutoCloseable>();
    private final List<DshPlugin> plugins = new ArrayList<DshPlugin>();
    private final List<URLClassLoader> pluginLoaders = new ArrayList<URLClassLoader>();
    private boolean started;

    public static DshRuntime load(ClassLoader loader) throws Exception {
        DshRuntime runtime = new DshRuntime();
        for (DshPlugin plugin : ServiceLoader.load(DshPlugin.class, loader)) runtime.install(plugin);
        return runtime;
    }

    public synchronized void install(DshPlugin plugin) throws Exception {
        if (plugin == null || plugin.id() == null || plugin.id().isBlank()) {
            throw new IllegalArgumentException("plugin id must not be blank");
        }
        if (plugins.stream().anyMatch(value -> plugin.id().equals(value.id()))) {
            throw new IllegalArgumentException("duplicate plugin: " + plugin.id());
        }
        plugin.start(new Context(plugin.id()));
        plugins.add(plugin);
    }

    public synchronized List<String> loadPlugins(Path directory) throws Exception {
        if (directory == null || !Files.isDirectory(directory)) return List.of();
        List<String> loaded = new ArrayList<String>();
        List<Path> jars;
        try (var paths = Files.list(directory)) {
            jars = paths.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        for (Path jar : jars) {
            URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, DshRuntime.class.getClassLoader());
            try {
                for (DshPlugin plugin : ServiceLoader.load(DshPlugin.class, loader)) {
                    install(plugin);
                    loaded.add(plugin.id());
                }
                pluginLoaders.add(loader);
            } catch (Exception exception) {
                loader.close();
                throw new IllegalStateException("failed to load plugin jar: " + jar.getFileName(), exception);
            }
        }
        return List.copyOf(loaded);
    }

    public void start() {
        started = true;
        eventBus.emit("runtime.started", this);
    }

    public EventBus events() {
        return eventBus;
    }

    public boolean isStarted() {
        return started;
    }

    public synchronized int pluginCount() {
        return plugins.size();
    }

    public synchronized List<String> pluginIds() {
        return plugins.stream().map(DshPlugin::id).toList();
    }

    public <T> T service(ServiceKey<T> key) {
        Object value = services.get(key);
        if (value == null) throw new IllegalStateException("missing service: " + key);
        return key.type().cast(value);
    }

    public synchronized <T> Registration provide(ServiceKey<T> key, T service) {
        if (started) throw new IllegalStateException("runtime already started");
        if (services.containsKey(key)) throw new IllegalStateException("duplicate service: " + key);
        services.put(key, service);
        return () -> services.remove(key, service);
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
        for (URLClassLoader loader : pluginLoaders) {
            try {
                loader.close();
            } catch (Exception ignored) {
            }
        }
        pluginLoaders.clear();
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
