package io.github.git13166956007.dsh.core;

import io.github.git13166956007.dsh.event.EventBus;
import io.github.git13166956007.dsh.plugin.DshPlugin;
import io.github.git13166956007.dsh.plugin.PluginContext;
import io.github.git13166956007.dsh.plugin.Registration;
import io.github.git13166956007.dsh.service.ServiceKey;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ServiceLoader;

public final class DshRuntime implements AutoCloseable {
    private final Map<ServiceKey<?>, Object> services = new HashMap<ServiceKey<?>, Object>();
    private final EventBus eventBus = new EventBus();
    private final Map<String, Deque<AutoCloseable>> pluginEffects = new LinkedHashMap<String, Deque<AutoCloseable>>();
    private final List<PluginHandle> plugins = new ArrayList<PluginHandle>();
    private final Set<Path> loadedPluginJars = new HashSet<Path>();
    private boolean started;

    public static DshRuntime load(ClassLoader loader) throws Exception {
        DshRuntime runtime = new DshRuntime();
        for (DshPlugin plugin : ServiceLoader.load(DshPlugin.class, loader)) runtime.install(plugin);
        return runtime;
    }

    public synchronized void install(DshPlugin plugin) throws Exception {
        install(plugin, null, null);
    }

    private void install(DshPlugin plugin, URLClassLoader loader, Path jar) throws Exception {
        validatePlugin(plugin);
        Set<String> installed = new HashSet<String>(pluginIds());
        for (String dependency : normalizedDependencies(plugin)) {
            if (!installed.contains(dependency)) {
                throw new IllegalArgumentException("plugin " + plugin.id() + " requires " + dependency);
            }
        }
        pluginEffects.put(plugin.id(), new ArrayDeque<AutoCloseable>());
        try {
            plugin.start(new Context(plugin.id()));
            plugins.add(new PluginHandle(plugin, loader, jar));
        } catch (Exception exception) {
            closeEffects(plugin.id());
            throw exception;
        }
    }

    public synchronized List<String> loadPlugins(Path directory) throws Exception {
        if (directory == null || !Files.isDirectory(directory)) return List.of();
        List<PluginCandidate> candidates = discover(directory);
        if (candidates.isEmpty()) return List.of();

        List<PluginCandidate> ordered;
        try {
            ordered = order(candidates);
        } catch (Exception exception) {
            closeLoaders(candidates);
            throw new IllegalStateException("failed to resolve plugin dependencies", exception);
        }
        List<String> loaded = new ArrayList<String>();
        try {
            for (PluginCandidate candidate : ordered) {
                install(candidate.plugin(), candidate.loader(), candidate.jar());
                loaded.add(candidate.plugin().id());
            }
            for (PluginCandidate candidate : candidates) loadedPluginJars.add(candidate.jar());
            return List.copyOf(loaded);
        } catch (Exception exception) {
            for (PluginCandidate candidate : candidates) {
                if (loaded.contains(candidate.plugin().id())) removePlugin(candidate.plugin().id());
            }
            closeLoaders(candidates);
            throw new IllegalStateException("failed to load plugin directory", exception);
        }
    }

    public synchronized List<String> reloadPlugins(Path directory) throws Exception {
        unloadPlugins();
        return loadPlugins(directory);
    }

    public synchronized List<String> unloadPlugins() {
        List<String> removed = new ArrayList<String>();
        for (int index = plugins.size() - 1; index >= 0; index--) {
            PluginHandle handle = plugins.get(index);
            if (handle.loader() == null) continue;
            removed.add(handle.plugin().id());
            removePlugin(handle.plugin().id());
            loadedPluginJars.remove(handle.jar());
            closeLoader(handle.loader());
        }
        return List.copyOf(removed);
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
        return plugins.stream().map(handle -> handle.plugin().id()).toList();
    }

    public synchronized Map<String, String> pluginVersions() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (PluginHandle handle : plugins) result.put(handle.plugin().id(), handle.plugin().version());
        return Map.copyOf(result);
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
    public synchronized void close() {
        for (int index = plugins.size() - 1; index >= 0; index--) {
            closeEffects(plugins.get(index).plugin().id());
        }
        for (PluginHandle handle : plugins) closeLoader(handle.loader());
        plugins.clear();
        pluginEffects.clear();
        loadedPluginJars.clear();
        services.clear();
        started = false;
    }

    private List<PluginCandidate> discover(Path directory) throws Exception {
        List<Path> jars;
        try (var paths = Files.list(directory)) {
            jars = paths.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        List<PluginCandidate> candidates = new ArrayList<PluginCandidate>();
        Set<String> existing = new HashSet<String>(pluginIds());
        try {
            for (Path jar : jars) {
                Path absoluteJar = jar.toAbsolutePath().normalize();
                if (loadedPluginJars.contains(absoluteJar)) continue;
                URLClassLoader loader = new URLClassLoader(new URL[]{jar.toAbsolutePath().toUri().toURL()},
                        DshRuntime.class.getClassLoader());
                try {
                    for (DshPlugin plugin : ServiceLoader.load(DshPlugin.class, loader)) {
                        validatePlugin(plugin);
                        if (!existing.add(plugin.id())) {
                            throw new IllegalArgumentException("duplicate plugin: " + plugin.id());
                        }
                        candidates.add(new PluginCandidate(plugin, loader, jar.toAbsolutePath().normalize()));
                    }
                } catch (Exception exception) {
                    closeLoader(loader);
                    throw exception;
                }
            }
        } catch (Exception exception) {
            closeLoaders(candidates);
            throw new IllegalStateException("failed to inspect plugin directory", exception);
        }
        return candidates;
    }

    private List<PluginCandidate> order(List<PluginCandidate> candidates) {
        Map<String, PluginCandidate> byId = new LinkedHashMap<String, PluginCandidate>();
        for (PluginCandidate candidate : candidates) byId.put(candidate.plugin().id(), candidate);
        Set<String> installed = new HashSet<String>(pluginIds());
        Map<String, VisitState> states = new HashMap<String, VisitState>();
        List<PluginCandidate> result = new ArrayList<PluginCandidate>();
        for (PluginCandidate candidate : candidates) visit(candidate, byId, installed, states, result);
        return result;
    }

    private void visit(PluginCandidate candidate, Map<String, PluginCandidate> byId, Set<String> installed,
                       Map<String, VisitState> states, List<PluginCandidate> result) {
        String id = candidate.plugin().id();
        VisitState state = states.get(id);
        if (state == VisitState.DONE) return;
        if (state == VisitState.VISITING) throw new IllegalArgumentException("plugin dependency cycle at: " + id);
        states.put(id, VisitState.VISITING);
        for (String dependency : normalizedDependencies(candidate.plugin())) {
            if (installed.contains(dependency)) continue;
            PluginCandidate dependencyCandidate = byId.get(dependency);
            if (dependencyCandidate == null) {
                throw new IllegalArgumentException("missing plugin dependency: " + id + " -> " + dependency);
            }
            visit(dependencyCandidate, byId, installed, states, result);
        }
        states.put(id, VisitState.DONE);
        result.add(candidate);
    }

    private void validatePlugin(DshPlugin plugin) {
        if (plugin == null || plugin.id() == null || plugin.id().isBlank()) {
            throw new IllegalArgumentException("plugin id must not be blank");
        }
        if (plugins.stream().anyMatch(handle -> plugin.id().equals(handle.plugin().id()))) {
            throw new IllegalArgumentException("duplicate plugin: " + plugin.id());
        }
        if (plugin.version() == null || plugin.version().isBlank()) {
            throw new IllegalArgumentException("plugin version must not be blank: " + plugin.id());
        }
    }

    private static Set<String> normalizedDependencies(DshPlugin plugin) {
        if (plugin.dependencies() == null) return Set.of();
        Set<String> result = new HashSet<String>();
        for (String dependency : plugin.dependencies()) {
            if (dependency == null || dependency.isBlank()) {
                throw new IllegalArgumentException("blank plugin dependency: " + plugin.id());
            }
            result.add(dependency.trim());
        }
        return result;
    }

    private void removePlugin(String id) {
        closeEffects(id);
        plugins.removeIf(handle -> handle.plugin().id().equals(id));
    }

    private void closeEffects(String id) {
        Deque<AutoCloseable> effects = pluginEffects.remove(id);
        if (effects == null) return;
        while (!effects.isEmpty()) {
            try {
                effects.pop().close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeLoaders(List<PluginCandidate> candidates) {
        Set<URLClassLoader> loaders = new HashSet<URLClassLoader>();
        for (PluginCandidate candidate : candidates) loaders.add(candidate.loader());
        loaders.forEach(DshRuntime::closeLoader);
    }

    private static void closeLoader(URLClassLoader loader) {
        if (loader == null) return;
        try {
            loader.close();
        } catch (Exception ignored) {
        }
    }

    private enum VisitState { VISITING, DONE }

    private record PluginCandidate(DshPlugin plugin, URLClassLoader loader, Path jar) { }

    private record PluginHandle(DshPlugin plugin, URLClassLoader loader, Path jar) { }

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
            if (closeable == null) throw new IllegalArgumentException("plugin effect must not be null");
            pluginEffects.computeIfAbsent(pluginId, ignored -> new ArrayDeque<AutoCloseable>()).push(closeable);
        }
    }
}
