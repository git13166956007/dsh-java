package io.github.git13166956007.dsh.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MemoryManagerTest {
    @Test
    void savesSearchesAndBuildsContextForOneSubject() throws Exception {
        MemoryManager manager = new MemoryManager(new InMemoryMemoryStore());
        manager.save("conversation", "c-1", "preference", "User prefers concise answers", null, 0.9);
        manager.save("conversation", "c-2", "fact", "Different conversation", null, 0.5);

        assertEquals(1, manager.search("conversation", "c-1", "concise", 10).size());
        assertTrue(manager.context("conversation", "c-1", "concise", 5).contains("User prefers concise answers"));
        assertEquals(1, manager.list("conversation", "c-2", 10).size());
    }
}
