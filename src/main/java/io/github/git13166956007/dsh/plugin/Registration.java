package io.github.git13166956007.dsh.plugin;

@FunctionalInterface
public interface Registration extends AutoCloseable {
    @Override
    void close();
}
