package io.github.git13166956007.dsh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

public final class DshSelfTest {
    private static final ServiceKey<String> GREETING = new ServiceKey<String>("greeting", String.class);

    public static void main(String[] args) throws Exception {
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
        if (!"hello".equals(runtime.service(GREETING))) throw new AssertionError("service not available");
        runtime.events().emit("ping", "ok");
        if (events.get() != 1) throw new AssertionError("event not delivered");
        runtime.close();

        Path session = Paths.get("out", "self-test.jsonl");
        Files.deleteIfExists(session);
        JsonlSessionStore store = new JsonlSessionStore(session);
        store.append("user_message", "hello\"world");
        String line = new String(Files.readAllBytes(session), "UTF-8");
        if (!line.contains("hello\\\"world")) throw new AssertionError("invalid JSONL escaping");
        Files.deleteIfExists(session);
        System.out.println("dsh-java self-test passed");
    }

}
