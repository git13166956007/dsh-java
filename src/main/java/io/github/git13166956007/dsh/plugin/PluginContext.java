package io.github.git13166956007.dsh.plugin;

import java.util.function.Consumer;
import io.github.git13166956007.dsh.service.ServiceKey;

public interface PluginContext {
    <T> T service(ServiceKey<T> key);

    <T> Registration provide(ServiceKey<T> key, T service);

    Registration on(String event, Consumer<Object> listener);

    void effect(AutoCloseable closeable);
}
