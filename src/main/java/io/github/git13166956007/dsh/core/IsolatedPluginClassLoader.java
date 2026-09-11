package io.github.git13166956007.dsh.core;

import java.net.URL;
import java.net.URLClassLoader;

/** Child-first loader for plugin implementation classes while sharing the DSH SPI. */
final class IsolatedPluginClassLoader extends URLClassLoader {
    IsolatedPluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            if (isShared(name)) return super.loadClass(name, resolve);
            try {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            } catch (ClassNotFoundException exception) {
                return super.loadClass(name, resolve);
            }
        }
    }

    private static boolean isShared(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("io.github.git13166956007.dsh.plugin.")
                || name.startsWith("io.github.git13166956007.dsh.service.")
                || name.startsWith("io.github.git13166956007.dsh.event.");
    }
}
