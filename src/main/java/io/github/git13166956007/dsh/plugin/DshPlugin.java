package io.github.git13166956007.dsh.plugin;

import java.util.Set;

public interface DshPlugin {
    String id();

    void start(PluginContext context) throws Exception;

    default String version() {
        return "0.1.0";
    }

    default Set<String> dependencies() {
        return Set.of();
    }
}
