package io.github.git13166956007.dsh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.git13166956007.dsh.plugin.DshPlugin;
import io.github.git13166956007.dsh.plugin.PluginContext;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DshRuntimePluginLoadingTest {
    @Test
    void loadsPluginsDeclaredByJarServiceFile() throws Exception {
        Path directory = Files.createTempDirectory("dsh-plugins");
        Path jar = directory.resolve("demo.jar");
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream archive = new JarOutputStream(output)) {
            archive.putNextEntry(new JarEntry("META-INF/services/io.github.git13166956007.dsh.plugin.DshPlugin"));
            archive.write(DemoPlugin.class.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            archive.closeEntry();
        }

        DshRuntime runtime = new DshRuntime();
        runtime.start();
        assertEquals(java.util.List.of("jar-demo"), runtime.loadPlugins(directory));
        assertEquals(java.util.List.of(), runtime.loadPlugins(directory));
        assertEquals(java.util.List.of("jar-demo"), runtime.pluginIds());
        runtime.close();
    }

    @Test
    void ordersJarPluginsByDependenciesAndReloadsTheirEffects() throws Exception {
        Path directory = Files.createTempDirectory("dsh-plugin-deps");
        writeServiceJar(directory.resolve("a-dependent.jar"), DependentPlugin.class);
        writeServiceJar(directory.resolve("z-dependency.jar"), DependencyPlugin.class);
        DependencyPlugin.starts.set(0);
        DependentPlugin.starts.set(0);
        DependentPlugin.closes.set(0);

        DshRuntime runtime = new DshRuntime();
        runtime.start();

        assertEquals(java.util.List.of("jar-dependency", "jar-dependent"), runtime.loadPlugins(directory));
        assertEquals(1, DependencyPlugin.starts.get());
        assertEquals(1, DependentPlugin.starts.get());
        assertEquals(java.util.List.of("jar-dependent", "jar-dependency"), runtime.unloadPlugins());
        assertEquals(1, DependentPlugin.closes.get());

        assertEquals(java.util.List.of("jar-dependency", "jar-dependent"), runtime.reloadPlugins(directory));
        assertEquals(2, DependencyPlugin.starts.get());
        assertEquals(2, DependentPlugin.starts.get());
        runtime.close();
    }

    @Test
    void rejectsMissingPluginDependencies() throws Exception {
        Path directory = Files.createTempDirectory("dsh-plugin-missing");
        writeServiceJar(directory.resolve("missing.jar"), MissingDependencyPlugin.class);

        DshRuntime runtime = new DshRuntime();
        runtime.start();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> runtime.loadPlugins(directory));
        assertEquals(java.util.List.of(), runtime.pluginIds());
        runtime.close();
    }

    private static void writeServiceJar(Path jar, Class<?> plugin) throws Exception {
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream archive = new JarOutputStream(output)) {
            archive.putNextEntry(new JarEntry("META-INF/services/io.github.git13166956007.dsh.plugin.DshPlugin"));
            archive.write(plugin.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            archive.closeEntry();
        }
    }

    public static final class DemoPlugin implements DshPlugin {
        @Override
        public String id() {
            return "jar-demo";
        }

        @Override
        public void start(PluginContext context) {
        }
    }

    public static final class DependencyPlugin implements DshPlugin {
        private static final AtomicInteger starts = new AtomicInteger();

        @Override
        public String id() {
            return "jar-dependency";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public void start(PluginContext context) {
            starts.incrementAndGet();
        }
    }

    public static final class DependentPlugin implements DshPlugin {
        private static final AtomicInteger starts = new AtomicInteger();
        private static final AtomicInteger closes = new AtomicInteger();

        @Override
        public String id() {
            return "jar-dependent";
        }

        @Override
        public Set<String> dependencies() {
            return Set.of("jar-dependency");
        }

        @Override
        public void start(PluginContext context) {
            starts.incrementAndGet();
            context.effect(() -> closes.incrementAndGet());
        }
    }

    public static final class MissingDependencyPlugin implements DshPlugin {
        @Override
        public String id() {
            return "jar-missing";
        }

        @Override
        public Set<String> dependencies() {
            return Set.of("does-not-exist");
        }

        @Override
        public void start(PluginContext context) {
        }
    }
}
