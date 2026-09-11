package io.github.git13166956007.dsh.plugin;

public record PluginVersion(int major, int minor, int patch) implements Comparable<PluginVersion> {
    public static PluginVersion parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("plugin version must not be blank");
        String numeric = value.trim().replaceFirst("^[vV]", "").split("[-+]", 2)[0];
        String[] parts = numeric.split("\\.");
        if (parts.length > 3) throw new IllegalArgumentException("invalid plugin version: " + value);
        int major = Integer.parseInt(parts[0]);
        int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        if (major < 0 || minor < 0 || patch < 0) throw new IllegalArgumentException("invalid plugin version: " + value);
        return new PluginVersion(major, minor, patch);
    }

    public static boolean satisfies(String actual, String range) {
        if (range == null || range.isBlank() || "*".equals(range.trim())) return true;
        PluginVersion candidate = parse(actual);
        for (String token : range.trim().split("\\s+")) {
            if (token.isBlank()) continue;
            if (token.startsWith("^")) {
                PluginVersion floor = parse(token.substring(1));
                if (candidate.compareTo(floor) < 0 || candidate.major() != floor.major()) return false;
            } else if (token.startsWith("~")) {
                PluginVersion floor = parse(token.substring(1));
                if (candidate.compareTo(floor) < 0 || candidate.major() != floor.major()
                        || candidate.minor() != floor.minor()) return false;
            } else if (token.startsWith(">=")) {
                if (candidate.compareTo(parse(token.substring(2))) < 0) return false;
            } else if (token.startsWith("<=")) {
                if (candidate.compareTo(parse(token.substring(2))) > 0) return false;
            } else if (token.startsWith(">")) {
                if (candidate.compareTo(parse(token.substring(1))) <= 0) return false;
            } else if (token.startsWith("<")) {
                if (candidate.compareTo(parse(token.substring(1))) >= 0) return false;
            } else if (candidate.compareTo(parse(token)) != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int compareTo(PluginVersion other) {
        int majorResult = Integer.compare(major, other.major);
        if (majorResult != 0) return majorResult;
        int minorResult = Integer.compare(minor, other.minor);
        return minorResult == 0 ? Integer.compare(patch, other.patch) : minorResult;
    }
}
