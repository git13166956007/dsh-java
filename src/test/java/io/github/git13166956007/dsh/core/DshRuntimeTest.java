package io.github.git13166956007.dsh.core;

import java.io.IOException;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import io.github.git13166956007.dsh.plugin.DshPlugin;
import io.github.git13166956007.dsh.plugin.PluginContext;
import io.github.git13166956007.dsh.plugin.PluginLease;
import io.github.git13166956007.dsh.service.ServiceKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class DshRuntimeTest {
    private static final ServiceKey<String> GREETING = new ServiceKey<String>("greeting", String.class);

    @Test
    void pluginCanRegisterServicesAndEvents() throws Exception {
        AtomicInteger events = new AtomicInteger();
        DshRuntime runtime = new DshRuntime();
        runtime.install(new DshPlugin() {
            @Override
            public String id() {
                return "self-test";
            }

            @Override
            public void start(PluginContext context) {
                context.provide(GREETING, "hello");
                context.on("ping", payload -> {
                    if (!"ok".equals(payload)) throw new AssertionError("bad payload");
                    events.incrementAndGet();
                });
            }
        });
        runtime.start();
        assertEquals("hello", runtime.service(GREETING));
        runtime.events().emit("ping", "ok");
        assertEquals(1, events.get());
        assertTrue(runtime.isStarted());
        runtime.close();
    }

    @Test
    void negotiatesCapabilitiesAndWaitsForActiveLeaseBeforeUninstall() throws Exception {
        DshRuntime runtime = new DshRuntime();
        runtime.install(new DshPlugin() {
            @Override public String id() { return "provider"; }
            @Override public String version() { return "1.2.0"; }
            @Override public Set<String> capabilities() { return Set.of("maps"); }
            @Override public void start(PluginContext context) { }
        });
        AtomicInteger started = new AtomicInteger();
        runtime.install(new DshPlugin() {
            @Override public String id() { return "consumer"; }
            @Override public Set<String> dependencies() { return Set.of("provider"); }
            @Override public Map<String, String> dependencyVersions() { return Map.of("provider", ">=1.0.0 <2.0.0"); }
            @Override public Set<String> requiredCapabilities() { return Set.of("maps"); }
            @Override public void start(PluginContext context) {
                context.requireCapability("maps");
                started.incrementAndGet();
            }
        });
        assertEquals(1, started.get());

        PluginLease lease = runtime.acquirePlugin("consumer");
        CompletableFuture<Boolean> removed = CompletableFuture.supplyAsync(() -> runtime.uninstall("consumer"));
        Thread.sleep(40);
        assertEquals(false, removed.isDone());
        lease.close();
        assertEquals(true, removed.get());
        runtime.close();
    }

    @Test
    void rejectsIncompatibleDependencyVersion() throws Exception {
        DshRuntime runtime = new DshRuntime();
        runtime.install(new DshPlugin() {
            @Override public String id() { return "provider-version"; }
            @Override public String version() { return "1.0.0"; }
            @Override public void start(PluginContext context) { }
        });
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> runtime.install(new DshPlugin() {
            @Override public String id() { return "consumer-version"; }
            @Override public Set<String> dependencies() { return Set.of("provider-version"); }
            @Override public Map<String, String> dependencyVersions() { return Map.of("provider-version", ">=2.0.0"); }
            @Override public void start(PluginContext context) { }
        }));
        runtime.close();
    }

    @Test
    void replacementCannotRemoveCapabilityRequiredByAnInstalledConsumer() throws Exception {
        DshRuntime runtime = new DshRuntime();
        runtime.install(new DshPlugin() {
            @Override public String id() { return "cap-provider"; }
            @Override public Set<String> capabilities() { return Set.of("maps"); }
            @Override public void start(PluginContext context) { }
        });
        runtime.install(new DshPlugin() {
            @Override public String id() { return "cap-consumer"; }
            @Override public Set<String> dependencies() { return Set.of("cap-provider"); }
            @Override public Set<String> requiredCapabilities() { return Set.of("maps"); }
            @Override public void start(PluginContext context) { }
        });

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> runtime.replace("cap-provider",
                new DshPlugin() {
                    @Override public String id() { return "cap-provider"; }
                    @Override public String version() { return "2.0.0"; }
                    @Override public void start(PluginContext context) { }
                }));
        assertEquals(Set.of("cap-provider", "cap-consumer"), Set.copyOf(runtime.pluginIds()));
        runtime.close();
    }

    @Test
    void duplicateLeaseCloseDoesNotCorruptQuiescence() throws Exception {
        DshRuntime runtime = new DshRuntime();
        runtime.install(new DshPlugin() {
            @Override public String id() { return "lease-plugin"; }
            @Override public void start(PluginContext context) { }
        });
        PluginLease lease = runtime.acquirePlugin("lease-plugin");
        lease.close();
        lease.close();
        assertTrue(runtime.uninstall("lease-plugin"));
        runtime.close();
    }

    @Test
    void replacementFailureRestoresThePreviousPlugin() throws Exception {
        DshRuntime runtime = new DshRuntime();
        runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                "replaceable", GREETING, "old"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> runtime.replace("replaceable",
                new DshPlugin() {
                    @Override public String id() { return "replaceable"; }
                    @Override public void start(PluginContext context) {
                        throw new IllegalStateException("replacement failed");
                    }
                }));
        assertEquals("old", runtime.service(GREETING));
        assertEquals(java.util.List.of("replaceable"), runtime.pluginIds());
        runtime.close();
    }

    @Test
    void replacingDynamicPluginReleasesItsJarForLaterReload() throws Exception {
        Path root = Files.createTempDirectory("dsh-plugin-replace");
        Path plugins = root.resolve("plugins");
        Files.createDirectories(plugins);
        writeServiceJar(plugins.resolve("demo.jar"), DshRuntimePluginLoadingTest.DemoPlugin.class);
        DshRuntime runtime = new DshRuntime();
        try {
            assertEquals(java.util.List.of("jar-demo"), runtime.loadPlugins(plugins));
            runtime.replace("jar-demo", new DshPlugin() {
                @Override public String id() { return "jar-demo"; }
                @Override public void start(PluginContext context) { }
            });
            assertTrue(runtime.uninstall("jar-demo"));
            assertEquals(java.util.List.of("jar-demo"), runtime.loadPlugins(plugins));
        } finally {
            runtime.close();
            deleteTree(root);
        }
    }

    @Test
    void capabilityProviderCannotBeRemovedWhileAConsumerRequiresIt() throws Exception {
        DshRuntime runtime = new DshRuntime();
        runtime.install(new DshPlugin() {
            @Override public String id() { return "capability-source"; }
            @Override public Set<String> capabilities() { return Set.of("filesystem"); }
            @Override public void start(PluginContext context) { }
        });
        runtime.install(new DshPlugin() {
            @Override public String id() { return "capability-user"; }
            @Override public Set<String> requiredCapabilities() { return Set.of("filesystem"); }
            @Override public void start(PluginContext context) { }
        });

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> runtime.uninstall("capability-source"));
        assertEquals(2, runtime.pluginCount());
        runtime.close();
    }

    @Test
    void loadsServiceProviderPluginsThroughAnIsolatedClassLoader() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "JDK compiler is required for dynamic plugin test");
        Path root = Files.createTempDirectory("dsh-plugin-test");
        Path source = root.resolve("src");
        Path classes = root.resolve("classes");
        Path pluginDirectory = root.resolve("plugins");
        Files.createDirectories(source.resolve("fixture"));
        Files.createDirectories(classes);
        Files.createDirectories(pluginDirectory);
        Files.writeString(source.resolve("fixture/Provider.java"), """
                package fixture;
                import io.github.git13166956007.dsh.plugin.DshPlugin;
                import io.github.git13166956007.dsh.plugin.PluginContext;
                import java.util.Set;
                public final class Provider implements DshPlugin {
                    public String id() { return "z-provider"; }
                    public Set<String> capabilities() { return Set.of("dynamic.maps"); }
                    public void start(PluginContext context) { }
                }
                """);
        Files.writeString(source.resolve("fixture/Consumer.java"), """
                package fixture;
                import io.github.git13166956007.dsh.plugin.DshPlugin;
                import io.github.git13166956007.dsh.plugin.PluginContext;
                import java.util.Set;
                public final class Consumer implements DshPlugin {
                    public String id() { return "a-consumer"; }
                    public Set<String> requiredCapabilities() { return Set.of("dynamic.maps"); }
                    public void start(PluginContext context) { context.requireCapability("dynamic.maps"); }
                }
                """);
        int result = compiler.run(null, null, null, "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(), source.resolve("fixture/Provider.java").toString(),
                source.resolve("fixture/Consumer.java").toString());
        assertEquals(0, result);
        Path jar = pluginDirectory.resolve("dynamic.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String entry = classes.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                    output.putNextEntry(new JarEntry(entry));
                    Files.copy(file, output);
                    output.closeEntry();
                }
            }
            output.putNextEntry(new JarEntry("META-INF/services/io.github.git13166956007.dsh.plugin.DshPlugin"));
            output.write("fixture.Consumer\nfixture.Provider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }

        DshRuntime runtime = new DshRuntime();
        try {
            assertEquals(java.util.List.of("z-provider", "a-consumer"), runtime.loadPlugins(pluginDirectory));
            assertTrue(runtime.pluginInfo().stream().allMatch(DshRuntime.PluginInfo::dynamic));
            assertEquals(java.util.List.of("a-consumer", "z-provider"), runtime.unloadPlugins());
            assertEquals(0, runtime.pluginCount());
        } finally {
            runtime.close();
            deleteTree(root);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    private static void writeServiceJar(Path jar, Class<?> plugin) throws Exception {
        try (java.io.OutputStream output = Files.newOutputStream(jar);
             JarOutputStream archive = new JarOutputStream(output)) {
            archive.putNextEntry(new JarEntry("META-INF/services/io.github.git13166956007.dsh.plugin.DshPlugin"));
            archive.write(plugin.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            archive.closeEntry();
        }
    }

}
