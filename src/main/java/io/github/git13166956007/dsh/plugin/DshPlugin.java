package io.github.git13166956007.dsh.plugin;

import java.util.Set;
import java.util.Map;

public interface DshPlugin {
    String id();

    void start(PluginContext context) throws Exception;

    default String version() {
        return "0.1.0";
    }

    default Set<String> dependencies() {
        return Set.of();
    }

    default Map<String, String> dependencyVersions() {
        return Map.of();
    }

    default Set<String> capabilities() {
        return Set.of();
    }

    default Set<String> requiredCapabilities() {
        return Set.of();
    }
}
