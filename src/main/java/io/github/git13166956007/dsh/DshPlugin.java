package io.github.git13166956007.dsh;

public interface DshPlugin {
    String id();

    void start(PluginContext context) throws Exception;
}
