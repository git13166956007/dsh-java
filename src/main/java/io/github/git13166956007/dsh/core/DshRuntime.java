package io.github.git13166956007.dsh.core;

import io.github.git13166956007.dsh.event.EventBus;
import io.github.git13166956007.dsh.event.EventJournal;
import io.github.git13166956007.dsh.event.RuntimeEvents;
import io.github.git13166956007.dsh.core.scope.Scope;
import io.github.git13166956007.dsh.plugin.DshPlugin;
import io.github.git13166956007.dsh.plugin.PluginContext;
import io.github.git13166956007.dsh.plugin.PluginLease;
import io.github.git13166956007.dsh.plugin.PluginVersion;
import io.github.git13166956007.dsh.plugin.Registration;
import io.github.git13166956007.dsh.service.ServiceKey;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class DshRuntime implements AutoCloseable {
    private final Scope rootScope;
    private final EventBus eventBus;
    private final Map<String, Scope> pluginScopes = new LinkedHashMap<String, Scope>();
    private final List<PluginHandle> plugins = new ArrayList<PluginHandle>();
    private final Set<Path> loadedPluginJars = new HashSet<Path>();
    private final long quiesceTimeoutMillis = 5000;
    private volatile boolean started;

    public DshRuntime() {
        this(new Scope("runtime"), null);
    }

    public DshRuntime(Scope rootScope) {
        this(rootScope, null);
    }

    public DshRuntime(Scope rootScope, EventJournal journal) {
        if (rootScope == null) throw new IllegalArgumentException("runtime scope must not be null");
        this.rootScope = rootScope;
        this.eventBus = new EventBus(journal);
    }

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
            String requiredVersion = plugin.dependencyVersions().get(dependency);
            if (requiredVersion != null && !PluginVersion.satisfies(versionOf(dependency), requiredVersion)) {
                throw new IllegalArgumentException("plugin " + plugin.id() + " requires " + dependency
                        + " version " + requiredVersion + ", found " + versionOf(dependency));
            }
        }
        validateCapabilities(plugin);
        Scope pluginScope = rootScope.child("plugin:" + plugin.id());
        pluginScopes.put(plugin.id(), pluginScope);
        PluginHandle handle = new PluginHandle(plugin, loader, jar);
        try {
            plugin.start(new Context(handle, pluginScope));
            plugins.add(handle);
        } catch (Exception exception) {
            pluginScopes.remove(plugin.id());
            pluginScope.close();
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
        List<PluginHandle> dynamic = plugins.stream().filter(handle -> handle.loader() != null).toList();
        Set<String> unloading = dynamic.stream().map(handle -> handle.plugin().id()).collect(java.util.stream.Collectors.toSet());
        for (PluginHandle handle : dynamic) {
            ensureNoCapabilityDependents(handle.plugin(), unloading);
        }
        List<PluginHandle> quiesced = new ArrayList<PluginHandle>();
        try {
            for (PluginHandle handle : dynamic) {
                if (!handle.quiesce(quiesceTimeoutMillis)) {
                    throw new IllegalStateException("plugin is still in use: " + handle.plugin().id());
                }
                quiesced.add(handle);
            }
        } catch (RuntimeException exception) {
            quiesced.forEach(PluginHandle::resume);
            throw exception;
        }
        List<String> removed = new ArrayList<String>();
        for (int index = quiesced.size() - 1; index >= 0; index--) {
            PluginHandle handle = quiesced.get(index);
            removed.add(handle.plugin().id());
            removePlugin(handle.plugin().id());
            loadedPluginJars.remove(handle.jar());
            closeLoader(handle.loader());
        }
        return List.copyOf(removed);
    }

    /** Removes one plugin and releases its Scope-owned services and effects. */
    public synchronized boolean uninstall(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) return false;
        PluginHandle target = plugins.stream()
                .filter(handle -> pluginId.equals(handle.plugin().id()))
                .findFirst().orElse(null);
        if (target == null) return false;
        for (PluginHandle handle : plugins) {
            if (handle == target) continue;
            if (normalizedDependencies(handle.plugin()).contains(pluginId)) {
                throw new IllegalStateException("plugin is required by " + handle.plugin().id() + ": " + pluginId);
            }
        }
        ensureNoCapabilityDependents(target.plugin(), Set.of(pluginId));
        if (!target.quiesce(quiesceTimeoutMillis)) {
            throw new IllegalStateException("plugin is still in use: " + pluginId);
        }
        removePlugin(pluginId);
        loadedPluginJars.remove(target.jar());
        closeLoader(target.loader());
        return true;
    }

    /** Installs a replacement after the old plugin has been cleanly uninstalled. */
    public synchronized void replace(String pluginId, DshPlugin replacement) throws Exception {
        if (replacement == null) throw new IllegalArgumentException("replacement plugin must not be null");
        if (!pluginId.equals(replacement.id())) {
            throw new IllegalArgumentException("replacement id must match: " + pluginId);
        }
        validatePluginMetadata(replacement);
        PluginHandle old = plugins.stream().filter(handle -> pluginId.equals(handle.plugin().id())).findFirst().orElse(null);
        if (old == null) throw new IllegalArgumentException("unknown plugin: " + pluginId);
        validateReplacementDependencies(pluginId, replacement);
        validateReplacementCapabilities(pluginId, replacement);
        if (!old.quiesce(quiesceTimeoutMillis)) throw new IllegalStateException("plugin is still in use: " + pluginId);
        Path oldJar = old.jar();
        removePlugin(pluginId);
        if (oldJar != null) loadedPluginJars.remove(oldJar);
        boolean replacementInstalled = false;
        try {
            install(replacement, null, null);
            replacementInstalled = true;
        } catch (Exception exception) {
            try {
                install(old.plugin(), old.loader(), old.jar());
                if (oldJar != null) loadedPluginJars.add(oldJar);
            } catch (Exception rollback) {
                exception.addSuppressed(rollback);
            }
            throw exception;
        } finally {
            if (replacementInstalled) closeLoader(old.loader());
        }
    }

    public synchronized void start() {
        if (started) return;
        try {
            eventBus.rewrite(RuntimeEvents.LIFECYCLE,
                    new RuntimeEvents.LifecycleEvent(RuntimeEvents.LifecycleEvent.Phase.STARTING, "dsh-java"));
        } catch (Exception exception) {
            throw new IllegalStateException("runtime start rejected", exception);
        }
        try {
            started = true;
            eventBus.emit("runtime.started", this);
            eventBus.rewrite(RuntimeEvents.LIFECYCLE,
                    new RuntimeEvents.LifecycleEvent(RuntimeEvents.LifecycleEvent.Phase.STARTED, "dsh-java"));
        } catch (Exception exception) {
            started = false;
            throw new IllegalStateException("runtime start lifecycle failed", exception);
        }
    }

    public EventBus events() {
        return eventBus;
    }

    public boolean isStarted() {
        return started;
    }

    public Scope scope() {
        return rootScope;
    }

    public Scope childScope(String id) {
        return rootScope.child(id);
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

    public synchronized List<PluginInfo> pluginInfo() {
        return plugins.stream().map(handle -> new PluginInfo(handle.plugin().id(), handle.plugin().version(),
                handle.jar() == null ? null : handle.jar().toString(), handle.jar() != null)).toList();
    }

    public synchronized PluginLease acquirePlugin(String pluginId) {
        for (PluginHandle handle : plugins) {
            if (handle.plugin().id().equals(pluginId)) return handle.acquire();
        }
        throw new IllegalArgumentException("unknown plugin: " + pluginId);
    }

    public <T> T service(ServiceKey<T> key) {
        return rootScope.resolve(key);
    }

    public synchronized <T> Registration provide(ServiceKey<T> key, T service) {
        if (started) throw new IllegalStateException("runtime already started");
        return rootScope.provide(key, service);
    }

    @Override
    public synchronized void close() {
        boolean wasStarted = started;
        if (wasStarted) {
            eventBus.emit("runtime.stopping", this);
            try {
                eventBus.waterfall(RuntimeEvents.LIFECYCLE,
                        new RuntimeEvents.LifecycleEvent(RuntimeEvents.LifecycleEvent.Phase.STOPPING, "dsh-java"));
            } catch (Exception ignored) { }
        }
        for (int index = plugins.size() - 1; index >= 0; index--) {
            plugins.get(index).quiesce(quiesceTimeoutMillis);
            Scope pluginScope = pluginScopes.remove(plugins.get(index).plugin().id());
            if (pluginScope != null) pluginScope.close();
        }
        for (PluginHandle handle : plugins) closeLoader(handle.loader());
        plugins.clear();
        loadedPluginJars.clear();
        rootScope.close();
        started = false;
        if (wasStarted) {
            eventBus.emit("runtime.stopped", this);
            try {
                eventBus.waterfall(RuntimeEvents.LIFECYCLE,
                        new RuntimeEvents.LifecycleEvent(RuntimeEvents.LifecycleEvent.Phase.STOPPED, "dsh-java"));
            } catch (Exception ignored) { }
        }
        eventBus.close();
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
                URLClassLoader loader = new IsolatedPluginClassLoader(new URL[]{jar.toAbsolutePath().toUri().toURL()},
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
        Set<String> availableCapabilities = new HashSet<String>();
        for (String installedId : installed) {
            PluginCandidate installedCandidate = byId.get(installedId);
            if (installedCandidate != null) availableCapabilities.addAll(installedCandidate.plugin().capabilities());
            else {
                for (PluginHandle handle : plugins) {
                    if (installedId.equals(handle.plugin().id())) availableCapabilities.addAll(handle.plugin().capabilities());
                }
            }
        }
        for (PluginCandidate resolved : result) availableCapabilities.addAll(resolved.plugin().capabilities());
        availableCapabilities.addAll(candidate.plugin().capabilities());
        for (String required : candidate.plugin().requiredCapabilities()) {
            if (availableCapabilities.contains(required)) continue;
            PluginCandidate provider = byId.values().stream()
                    .filter(value -> !value.plugin().id().equals(id))
                    .filter(value -> value.plugin().capabilities().contains(required))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "missing plugin capability: " + id + " -> " + required));
            visit(provider, byId, installed, states, result);
            availableCapabilities.addAll(provider.plugin().capabilities());
        }
        states.put(id, VisitState.DONE);
        result.add(candidate);
        installed.add(id);
    }

    private void validatePlugin(DshPlugin plugin) {
        validatePluginMetadata(plugin);
        if (plugins.stream().anyMatch(handle -> plugin.id().equals(handle.plugin().id()))) {
            throw new IllegalArgumentException("duplicate plugin: " + plugin.id());
        }
    }

    private void validatePluginMetadata(DshPlugin plugin) {
        if (plugin == null || plugin.id() == null || plugin.id().isBlank()) {
            throw new IllegalArgumentException("plugin id must not be blank");
        }
        if (plugin.version() == null || plugin.version().isBlank()) {
            throw new IllegalArgumentException("plugin version must not be blank: " + plugin.id());
        }
        PluginVersion.parse(plugin.version());
        if (plugin.dependencyVersions() == null) throw new IllegalArgumentException("dependency versions must not be null");
        if (plugin.capabilities() == null || plugin.requiredCapabilities() == null) {
            throw new IllegalArgumentException("plugin capabilities must not be null: " + plugin.id());
        }
    }

    private void validateCapabilities(DshPlugin plugin) {
        Set<String> available = availableCapabilities();
        for (String required : plugin.requiredCapabilities()) {
            if (required == null || required.isBlank()) throw new IllegalArgumentException("blank required capability: " + plugin.id());
            if (!available.contains(required.trim())) {
                throw new IllegalArgumentException("plugin " + plugin.id() + " requires capability " + required);
            }
        }
    }

    private Set<String> availableCapabilities() {
        Set<String> result = new HashSet<String>();
        for (PluginHandle handle : plugins) result.addAll(handle.plugin().capabilities());
        return result;
    }

    private String versionOf(String id) {
        return plugins.stream().filter(handle -> id.equals(handle.plugin().id()))
                .map(handle -> handle.plugin().version()).findFirst().orElse(null);
    }

    private void validateReplacementDependencies(String id, DshPlugin replacement) {
        Set<String> installed = new HashSet<String>(pluginIds());
        installed.remove(id);
        for (String dependency : normalizedDependencies(replacement)) {
            if (!installed.contains(dependency) && !dependency.equals(id)) {
                throw new IllegalArgumentException("replacement requires missing plugin: " + dependency);
            }
            if (!dependency.equals(id)) {
                String requiredVersion = replacement.dependencyVersions().get(dependency);
                if (requiredVersion != null && !PluginVersion.satisfies(versionOf(dependency), requiredVersion)) {
                    throw new IllegalArgumentException("replacement requires " + dependency + " version " + requiredVersion);
                }
            }
        }
        for (PluginHandle handle : plugins) {
            if (handle.plugin().id().equals(id)) continue;
            if (normalizedDependencies(handle.plugin()).contains(id)) {
                String range = handle.plugin().dependencyVersions().get(id);
                if (range != null && !PluginVersion.satisfies(replacement.version(), range)) {
                    throw new IllegalArgumentException("replacement version " + replacement.version()
                            + " does not satisfy " + handle.plugin().id() + " requirement " + range);
                }
            }
        }
    }

    private void validateReplacementCapabilities(String id, DshPlugin replacement) {
        Set<String> available = new HashSet<String>();
        for (PluginHandle handle : plugins) {
            if (!handle.plugin().id().equals(id)) available.addAll(handle.plugin().capabilities());
        }
        for (String required : replacement.requiredCapabilities()) {
            if (!available.contains(required)) {
                throw new IllegalArgumentException("replacement requires missing capability: " + required);
            }
        }
        available.addAll(replacement.capabilities());
        for (PluginHandle handle : plugins) {
            if (handle.plugin().id().equals(id)) continue;
            for (String required : handle.plugin().requiredCapabilities()) {
                if (!available.contains(required)) {
                    throw new IllegalArgumentException("replacement removes capability " + required
                            + " required by " + handle.plugin().id());
                }
            }
        }
    }

    private void ensureNoCapabilityDependents(DshPlugin target, Set<String> removing) {
        Set<String> capabilities = target.capabilities();
        if (capabilities == null || capabilities.isEmpty()) return;
        for (PluginHandle handle : plugins) {
            if (removing.contains(handle.plugin().id())) continue;
            for (String required : handle.plugin().requiredCapabilities()) {
                if (capabilities.contains(required)) {
                    throw new IllegalStateException("plugin " + handle.plugin().id()
                            + " requires capability " + required + " from " + target.id());
                }
            }
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
        Scope pluginScope = pluginScopes.remove(id);
        if (pluginScope != null) pluginScope.close();
        plugins.removeIf(handle -> handle.plugin().id().equals(id));
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

    private static final class PluginHandle {
        private final DshPlugin plugin;
        private final URLClassLoader loader;
        private final Path jar;
        private final AtomicInteger leases = new AtomicInteger();
        private volatile boolean quiescing;

        private PluginHandle(DshPlugin plugin, URLClassLoader loader, Path jar) {
            this.plugin = plugin;
            this.loader = loader;
            this.jar = jar;
        }

        private DshPlugin plugin() { return plugin; }
        private URLClassLoader loader() { return loader; }
        private Path jar() { return jar; }

        private synchronized PluginLease acquire() {
            if (quiescing) throw new IllegalStateException("plugin is quiescing: " + plugin.id());
            leases.incrementAndGet();
            java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
            return () -> {
                if (!released.compareAndSet(false, true)) return;
                synchronized (this) {
                    leases.decrementAndGet();
                    notifyAll();
                }
            };
        }

        private synchronized boolean quiesce(long timeoutMillis) {
            quiescing = true;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (leases.get() > 0 && System.nanoTime() < deadline) {
                try { wait(Math.max(1, Math.min(50, timeoutMillis))); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            boolean idle = leases.get() == 0;
            if (!idle) quiescing = false;
            return idle;
        }

        private synchronized void resume() {
            quiescing = false;
        }
    }

    public record PluginInfo(String id, String version, String jar, boolean dynamic) { }

    private final class Context implements PluginContext {
        private final String pluginId;
        private final Scope scope;
        private final PluginHandle handle;

        private Context(PluginHandle handle, Scope scope) {
            this.handle = handle;
            this.pluginId = handle.plugin().id();
            this.scope = scope;
        }

        @Override
        public <T> T service(ServiceKey<T> key) {
            try {
                return rootScope.resolve(key);
            } catch (IllegalStateException exception) {
                throw new IllegalStateException(pluginId + " requires " + key, exception);
            }
        }

        @Override
        public <T> Registration provide(ServiceKey<T> key, T service) {
            Registration registration = rootScope.provide(key, service);
            scope.effect(registration);
            return registration;
        }

        @Override
        public Registration on(String event, java.util.function.Consumer<Object> listener) {
            Registration registration = eventBus.on(event, listener);
            scope.effect(registration);
            return registration;
        }

        @Override
        public <T> Registration on(io.github.git13166956007.dsh.event.EventKey<T> event,
                                   io.github.git13166956007.dsh.event.EventHandler<T> listener) {
            Registration registration = eventBus.on(event, listener);
            scope.effect(registration);
            return registration;
        }

        @Override
        public void effect(AutoCloseable closeable) {
            if (closeable == null) throw new IllegalArgumentException("plugin effect must not be null");
            scope.effect(closeable);
        }

        @Override
        public void requireCapability(String capability) {
            if (capability == null || capability.isBlank() || !availableCapabilities().contains(capability.trim())) {
                throw new IllegalStateException(pluginId + " requires capability " + capability);
            }
        }

        @Override
        public Set<String> capabilities() {
            return Set.copyOf(availableCapabilities());
        }

        @Override
        public PluginLease acquire() {
            return handle.acquire();
        }
    }
}
