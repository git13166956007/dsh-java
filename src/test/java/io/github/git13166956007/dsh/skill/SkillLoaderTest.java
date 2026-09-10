package io.github.git13166956007.dsh.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SkillLoaderTest {
    @Test
    void loadsFrontMatterAndBody() throws Exception {
        Path directory = Files.createTempDirectory("dsh-skill").resolve("writer");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: Writer\nenabled: false\n---\n\nBe clear.");

        SkillInfo skill = new SkillLoader().load(directory.resolve("SKILL.md"));

        assertEquals("writer", skill.id());
        assertEquals("Writer", skill.name());
        assertFalse(skill.enabled());
        assertEquals("Be clear.", skill.content());
    }
}
