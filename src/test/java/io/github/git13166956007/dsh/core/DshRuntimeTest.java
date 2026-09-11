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

}
