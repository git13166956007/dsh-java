package io.github.git13166956007.dsh.tool;

import io.github.git13166956007.dsh.workspace.WorkspaceProfile;
import io.github.git13166956007.dsh.workspace.WorkspaceRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executes explicitly allowlisted programs from the configured workspace.
 * Commands are passed directly to ProcessBuilder; no shell is involved.
 */
public final class WorkspaceProcessToolProvider implements AutoCloseable {
    private static final String SOURCE = "workspace-process";
    private static final int MAX_ARGUMENTS = 64;
    private static final int MAX_ARGUMENT_LENGTH = 4096;
    private final WorkspaceRegistry workspaces;
    private final WorkspaceProfile fixedProfile;
    private final ObjectMapper objectMapper;
    private final ExecutorService readers = Executors.newCachedThreadPool();

    public WorkspaceProcessToolProvider(Path root, Set<String> allowedCommands, int maxTimeoutSeconds,
                                        long maxOutputBytes, ObjectMapper objectMapper) {
        Path initialized = initializeRoot(root);
        validateTimeout(maxTimeoutSeconds);
        validateBytes(maxOutputBytes);
        this.workspaces = null;
        this.fixedProfile = new WorkspaceProfile("fixed", "Fixed workspace", initialized.toString(), true, true,
                false, 1_000_000, 1_000_000, maxTimeoutSeconds, maxOutputBytes, normalizeCommands(allowedCommands));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public WorkspaceProcessToolProvider(WorkspaceRegistry workspaces, ObjectMapper objectMapper) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.fixedProfile = null;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void register(ToolRegistry tools) {
        Objects.requireNonNull(tools, "tools");
        tools.registerExternal(new ToolDefinition("workspace_exec",
                "Run an allowlisted program in the configured workspace without a shell.", schema(objectMapper)),
                this::execute, SOURCE, true);
    }

    private String execute(JsonNode arguments) throws Exception {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("workspace_exec arguments must be an object");
        }
        String command = arguments.path("command").asText(null);
        if (command == null || command.isBlank()) throw new IllegalArgumentException("command is required");
        command = command.trim();
        WorkspaceProfile profile = profile();
        if (command.contains("/") || command.contains("\\") || !profile.allowedCommands().contains(command)) {
            throw new IllegalArgumentException("command is not allowlisted: " + command);
        }

        List<String> commandLine = new ArrayList<String>();
        commandLine.add(command);
        JsonNode values = arguments.path("arguments");
        if (!values.isMissingNode() && !values.isNull()) {
            if (!values.isArray()) throw new IllegalArgumentException("arguments must be an array");
            if (values.size() > MAX_ARGUMENTS) throw new IllegalArgumentException("too many process arguments");
            for (JsonNode value : values) {
                if (!value.isTextual() || value.asText().length() > MAX_ARGUMENT_LENGTH) {
                    throw new IllegalArgumentException("process arguments must be short strings");
                }
                commandLine.add(value.asText());
            }
        }
        int timeoutSeconds = arguments.path("timeoutSeconds").asInt(profile.maxProcessTimeoutSeconds());
        if (timeoutSeconds < 1 || timeoutSeconds > profile.maxProcessTimeoutSeconds()) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and " + profile.maxProcessTimeoutSeconds());
        }

        long maxOutputBytes = profile.maxProcessOutputBytes();
        Process process = new ProcessBuilder(commandLine).directory(Path.of(profile.directory()).toFile()).redirectErrorStream(true).start();
        Future<ProcessOutput> output = readers.submit(() -> readOutput(process.getInputStream(), maxOutputBytes));
        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) destroyProcess(process);
            ProcessOutput captured = output.get(5, TimeUnit.SECONDS);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("command", command);
            ArrayNode args = result.putArray("arguments");
            commandLine.subList(1, commandLine.size()).forEach(args::add);
            result.put("exitCode", finished ? process.exitValue() : -1);
            result.put("timedOut", !finished);
            result.put("truncated", captured.truncated());
            result.put("output", captured.text());
            return objectMapper.writeValueAsString(result);
        } catch (InterruptedException exception) {
            output.cancel(true);
            destroyProcess(process);
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            output.cancel(true);
            destroyProcess(process);
            throw exception;
        }
    }

    private ProcessOutput readOutput(InputStream input, long maxOutputBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long remaining = maxOutputBytes;
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (remaining > 0) {
                int kept = (int) Math.min(remaining, read);
                output.write(buffer, 0, kept);
                remaining -= kept;
                if (kept < read) truncated = true;
            } else {
                truncated = true;
            }
        }
        return new ProcessOutput(output.toString(StandardCharsets.UTF_8), truncated);
    }

    private static void destroyProcess(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
        try { process.waitFor(2, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
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

    private static Set<String> normalizeCommands(Set<String> values) {
        if (values == null) return Set.of();
        Set<String> result = new LinkedHashSet<String>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String command = value.trim();
            if (command.contains("/") || command.contains("\\") || !command.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("invalid allowlisted command: " + command);
            }
            result.add(command);
        }
        return Set.copyOf(result);
    }

    private WorkspaceProfile profile() {
        return workspaces == null ? fixedProfile : workspaces.active();
    }

    private static void validateTimeout(int value) {
        if (value < 1 || value > 3600) {
            throw new IllegalArgumentException("maxTimeoutSeconds must be between 1 and 3600");
        }
    }

    private static void validateBytes(long value) {
        if (value < 1 || value > 50_000_000) {
            throw new IllegalArgumentException("maxOutputBytes must be between 1 and 50000000");
        }
    }

    private static ObjectNode schema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("command").put("type", "string");
        properties.putObject("arguments").put("type", "array").putObject("items").put("type", "string");
        properties.putObject("timeoutSeconds").put("type", "integer").put("minimum", 1);
        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public void close() {
        readers.shutdownNow();
    }

    private record ProcessOutput(String text, boolean truncated) {
    }
}
