package io.github.git13166956007.dsh.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
