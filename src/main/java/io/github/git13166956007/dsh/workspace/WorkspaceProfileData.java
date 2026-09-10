package io.github.git13166956007.dsh.workspace;

import java.util.Set;

record WorkspaceProfileData(String id, String name, String directory, boolean enabled, boolean active,
                            boolean writeEnabled, long maxReadBytes, long maxWriteBytes,
                            int maxProcessTimeoutSeconds, long maxProcessOutputBytes,
                            Set<String> allowedCommands) {
}
