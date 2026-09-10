package io.github.git13166956007.dsh.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sandboxed filesystem tools rooted at one configured workspace directory.
 */
public final class WorkspaceToolProvider {
    private static final String SOURCE = "workspace";
    private static final int MAX_LIST_ENTRIES = 1000;
    private final Path root;
    private final long maxReadBytes;
    private final long maxWriteBytes;
    private final boolean writeEnabled;
    private final ObjectMapper objectMapper;

    public WorkspaceToolProvider(Path root, long maxReadBytes, long maxWriteBytes,
                                 boolean writeEnabled, ObjectMapper objectMapper) {
        this.root = initializeRoot(root);
        if (maxReadBytes < 1 || maxReadBytes > 50_000_000) {
            throw new IllegalArgumentException("maxReadBytes must be between 1 and 50000000");
        }
        if (maxWriteBytes < 1 || maxWriteBytes > 50_000_000) {
            throw new IllegalArgumentException("maxWriteBytes must be between 1 and 50000000");
        }
        this.maxReadBytes = maxReadBytes;
        this.maxWriteBytes = maxWriteBytes;
        this.writeEnabled = writeEnabled;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void register(ToolRegistry tools) {
        Objects.requireNonNull(tools, "tools");
        tools.registerExternal(new ToolDefinition("workspace_list_files", "List files in the configured workspace.",
                listSchema(objectMapper)), this::listFiles, SOURCE, false);
        tools.registerExternal(new ToolDefinition("workspace_read_file", "Read a UTF-8 text file from the configured workspace.",
                fileSchema(objectMapper, "Read a workspace-relative file path.")), this::readFile, SOURCE, false);
        if (writeEnabled) {
            tools.registerExternal(new ToolDefinition("workspace_write_file", "Write UTF-8 text to a file in the configured workspace.",
                    writeSchema(objectMapper)), this::writeFile, SOURCE, true);
        }
    }

    private String listFiles(JsonNode arguments) throws Exception {
        JsonNode input = arguments == null ? objectMapper.createObjectNode() : arguments;
        Path directory = resolveExisting(input.path("path").asText("."));
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("workspace path is not a directory");
        boolean recursive = input.path("recursive").asBoolean(false);
        int limit = input.path("maxEntries").asInt(200);
        if (limit < 1 || limit > MAX_LIST_ENTRIES) {
            throw new IllegalArgumentException("maxEntries must be between 1 and " + MAX_LIST_ENTRIES);
        }

        var stream = recursive ? Files.walk(directory) : Files.list(directory);
        try (stream) {
            List<String> entries = stream
                    .filter(path -> !path.equals(directory))
                    .map(this::relative)
                    .sorted()
                    .limit(limit)
                    .toList();
            return objectMapper.writeValueAsString(entries);
        }
    }

    private String readFile(JsonNode arguments) throws Exception {
        Path file = resolveExisting(requiredPath(arguments));
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("workspace path is not a regular file");
        }
        long size = Files.size(file);
        if (size > maxReadBytes) {
            throw new IllegalArgumentException("file exceeds maxReadBytes: " + maxReadBytes);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private String writeFile(JsonNode arguments) throws Exception {
        if (!writeEnabled) throw new IllegalStateException("workspace write tools are disabled");
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("workspace_write_file arguments must be an object");
        }
        Path file = resolveForWrite(requiredPath(arguments));
        String content = arguments.path("content").asText(null);
        if (content == null) throw new IllegalArgumentException("content is required");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxWriteBytes) {
            throw new IllegalArgumentException("content exceeds maxWriteBytes: " + maxWriteBytes);
        }
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", relative(file));
        result.put("bytes", bytes.length);
        return objectMapper.writeValueAsString(result);
    }

    private Path resolveExisting(String value) throws IOException {
        Path candidate = resolveCandidate(value);
        Path real = candidate.toRealPath();
        ensureInsideRoot(real);
        return real;
    }

    private Path resolveForWrite(String value) throws IOException {
        Path candidate = resolveCandidate(value);
        Path parent = candidate.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("workspace file parent directory does not exist");
        }
        ensureInsideRoot(parent.toRealPath());
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(candidate)) throw new IllegalArgumentException("symbolic links are not allowed");
            ensureInsideRoot(candidate.toRealPath());
        }
        return candidate;
    }

    private Path resolveCandidate(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("workspace path must not be blank");
        Path path = Path.of(value);
        if (path.isAbsolute()) throw new IllegalArgumentException("absolute workspace paths are not allowed");
        Path candidate = root.resolve(path).normalize();
        ensureInsideRoot(candidate);
        return candidate;
    }

    private void ensureInsideRoot(Path path) {
        if (!path.startsWith(root)) throw new IllegalArgumentException("workspace path escapes configured root");
    }

    private String relative(Path path) {
        return root.relativize(path).toString().replace(path.getFileSystem().getSeparator().charAt(0), '/');
    }

    private static String requiredPath(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("workspace file arguments must be an object");
        }
        String path = arguments.path("path").asText(null);
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        return path;
    }

    private static Path initializeRoot(Path value) {
        if (value == null) throw new IllegalArgumentException("workspace root must not be null");
        try {
            Files.createDirectories(value);
            return value.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to initialize workspace root", exception);
        }
    }

    private static ObjectNode listSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("description", "Relative directory path; defaults to .");
        properties.putObject("recursive").put("type", "boolean");
        properties.putObject("maxEntries").put("type", "integer").put("minimum", 1).put("maximum", MAX_LIST_ENTRIES);
        return schema;
    }

    private static ObjectNode fileSchema(ObjectMapper mapper, String description) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("description", description);
        schema.putArray("required").add("path");
        return schema;
    }

    private static ObjectNode writeSchema(ObjectMapper mapper) {
        ObjectNode schema = fileSchema(mapper, "Relative file path to write.");
        ((ObjectNode) schema.get("properties")).putObject("content").put("type", "string");
        schema.putArray("required").add("path").add("content");
        return schema;
    }
}
