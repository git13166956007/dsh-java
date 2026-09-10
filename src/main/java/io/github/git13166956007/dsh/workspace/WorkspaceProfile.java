package io.github.git13166956007.dsh.workspace;

import java.util.Set;

public record WorkspaceProfile(String id, String name, String directory, boolean enabled, boolean active,
                               boolean writeEnabled, long maxReadBytes, long maxWriteBytes,
                               int maxProcessTimeoutSeconds, long maxProcessOutputBytes,
                               Set<String> allowedCommands) {
    public static WorkspaceProfile from(WorkspaceProfileData data) {
        return new WorkspaceProfile(data.id(), data.name(), data.directory(), data.enabled(), data.active(),
                data.writeEnabled(), data.maxReadBytes(), data.maxWriteBytes(), data.maxProcessTimeoutSeconds(),
                data.maxProcessOutputBytes(), Set.copyOf(data.allowedCommands()));
    }
}
