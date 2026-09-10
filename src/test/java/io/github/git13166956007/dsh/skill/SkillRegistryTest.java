package io.github.git13166956007.dsh.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SkillRegistryTest {
    @Test
    void enabledStateSurvivesRefreshAndRegistryRecreation() throws Exception {
        Path root = Files.createTempDirectory("dsh-skills");
        Path skill = Files.createDirectories(root.resolve("writer"));
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: Writer\nenabled: true\n---\n\nBe clear.");
        InMemorySkillStateStore store = new InMemorySkillStateStore();

        SkillRegistry first = new SkillRegistry(root.toString(), store);
        first.setEnabled("writer", false);
        first.refresh();
        assertFalse(first.find("writer").enabled());

        SkillRegistry second = new SkillRegistry(root.toString(), store);
        assertFalse(second.find("writer").enabled());
    }

    @Test
    void installsUpdatesAndRemovesSkillPackages() throws Exception {
        Path root = Files.createTempDirectory("dsh-managed-skills");
        InMemorySkillStateStore store = new InMemorySkillStateStore();
        SkillRegistry registry = new SkillRegistry(root.toString(), store);

        SkillInfo created = registry.install("research", "Research", "1.2.0", "Research tasks",
                "Use primary sources.", true);
        assertEquals("1.2.0", created.version());
        assertEquals("Use primary sources.", created.content());
        assertEquals("research", created.id());
        registry.setEnabled("research", false);

        SkillInfo updated = registry.install("research", "Research", "1.3.0", "Updated",
                "Verify every claim.", null);
        assertEquals("1.3.0", updated.version());
        assertEquals("Verify every claim.", updated.content());
        assertTrue(Files.exists(root.resolve("research/SKILL.md")));

        assertTrue(registry.remove("research"));
        assertFalse(Files.exists(root.resolve("research")));
        assertFalse(registry.remove("research"));

        SkillInfo reinstalled = registry.install("research", "Research", "1.4.0", "Fresh",
                "Use the updated instructions.", true);
        assertTrue(reinstalled.enabled());
    }

    @Test
    void rejectsUnsafeSkillIdsAndMetadata() throws Exception {
        SkillRegistry registry = new SkillRegistry(Files.createTempDirectory("dsh-managed-skills").toString());
        assertThrows(IllegalArgumentException.class,
                () -> registry.install("../outside", null, null, null, "content", true));
        assertThrows(IllegalArgumentException.class,
                () -> registry.install("valid", "bad\nname", null, null, "content", true));
    }
}
