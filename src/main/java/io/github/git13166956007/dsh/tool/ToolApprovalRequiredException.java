package io.github.git13166956007.dsh.tool;

public final class ToolApprovalRequiredException extends IllegalStateException {
    public ToolApprovalRequiredException(String toolName) {
        super("tool approval required: " + toolName);
    }
}
