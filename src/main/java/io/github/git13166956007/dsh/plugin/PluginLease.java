package io.github.git13166956007.dsh.plugin;

@FunctionalInterface
public interface PluginLease extends AutoCloseable {
    PluginLease NOOP = () -> { };

    @Override
    void close();
}
