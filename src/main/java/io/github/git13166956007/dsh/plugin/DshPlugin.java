package io.github.git13166956007.dsh.plugin;

public interface DshPlugin {
    String id();

    void start(PluginContext context) throws Exception;
}
