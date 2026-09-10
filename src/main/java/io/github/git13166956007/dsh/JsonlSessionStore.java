package io.github.git13166956007.dsh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class JsonlSessionStore {
    private final Path file;

    public JsonlSessionStore(Path file) {
        this.file = file;
    }

    public synchronized void append(String type, String data) throws IOException {
        String line = "{\"type\":\"" + escape(type) + "\",\"data\":\"" + escape(data) + "\"}\n";
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default: result.append(c);
            }
        }
        return result.toString();
    }
}
