package io.github.git13166956007.dsh.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SkillLoader {
    public SkillInfo load(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, String> frontMatter = new LinkedHashMap<String, String>();
        String body = raw;
        if (raw.startsWith("---\n") || raw.startsWith("---\r\n")) {
            int end = raw.indexOf("\n---", 4);
            if (end >= 0) {
                String header = raw.substring(4, end);
                for (String line : header.split("\\R")) {
                    int separator = line.indexOf(':');
                    if (separator > 0) frontMatter.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
                }
                body = raw.substring(Math.min(raw.length(), end + 4)).replaceFirst("^\\R", "").trim();
            }
        }
        String id = file.getParent().getFileName().toString();
        String name = frontMatter.getOrDefault("name", id);
        String version = frontMatter.getOrDefault("version", "0.1.0");
        String description = frontMatter.getOrDefault("description", "");
        if (description.isBlank()) description = firstParagraph(body);
        boolean enabled = !"false".equalsIgnoreCase(frontMatter.getOrDefault("enabled", "true"));
        List<String> resources = new ArrayList<String>();
        try (var paths = Files.walk(file.getParent())) {
            paths.filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !path.equals(file))
                    .map(path -> file.getParent().relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(resources::add);
        }
        return new SkillInfo(id, name, version, description, enabled, body, resources);
    }

    private static String firstParagraph(String body) {
        for (String line : body.split("\\R")) {
            if (!line.trim().isEmpty() && !line.trim().startsWith("#")) return line.trim();
        }
        return "Workspace skill";
    }
}
