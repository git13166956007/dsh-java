package io.github.git13166956007.dsh.core;

import java.util.concurrent.atomic.AtomicInteger;
import io.github.git13166956007.dsh.plugin.DshPlugin;
import io.github.git13166956007.dsh.plugin.PluginContext;
import io.github.git13166956007.dsh.plugin.PluginLease;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import io.github.git13166956007.dsh.service.ServiceKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

}
