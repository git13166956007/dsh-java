package io.github.git13166956007.dsh.core;

import java.util.concurrent.atomic.AtomicInteger;
import io.github.git13166956007.dsh.plugin.DshPlugin;
import io.github.git13166956007.dsh.plugin.PluginContext;
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

}
