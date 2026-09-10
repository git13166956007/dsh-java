package io.github.git13166956007.dsh.session;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlSessionStoreTest {
    @Test
    void appendEscapesJsonText() throws Exception {
        Path file = Files.createTempFile("dsh-session-", ".jsonl");
        try {
            new JsonlSessionStore(file).append("user_message", "hello\"world");
            String line = new String(Files.readAllBytes(file), "UTF-8");
            assertTrue(line.contains("hello\\\"world"));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
