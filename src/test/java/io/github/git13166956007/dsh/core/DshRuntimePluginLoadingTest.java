package io.github.git13166956007.dsh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.git13166956007.dsh.plugin.DshPlugin;
import io.github.git13166956007.dsh.plugin.PluginContext;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;

class DshRuntimePluginLoadingTest {
    @Test
    void loadsPluginsDeclaredByJarServiceFile() throws Exception {
        Path directory = Files.createTempDirectory("dsh-plugins");
        Path jar = directory.resolve("demo.jar");
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream archive = new JarOutputStream(output)) {
            archive.putNextEntry(new JarEntry("META-INF/services/io.github.git13166956007.dsh.plugin.DshPlugin"));
            archive.write(DemoPlugin.class.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            archive.closeEntry();
        }

        DshRuntime runtime = new DshRuntime();
        runtime.start();
        assertEquals(java.util.List.of("jar-demo"), runtime.loadPlugins(directory));
        assertEquals(java.util.List.of(), runtime.loadPlugins(directory));
        assertEquals(java.util.List.of("jar-demo"), runtime.pluginIds());
        runtime.close();
    }

    public static final class DemoPlugin implements DshPlugin {
        @Override
        public String id() {
            return "jar-demo";
        }

        @Override
        public void start(PluginContext context) {
        }
    }
}
