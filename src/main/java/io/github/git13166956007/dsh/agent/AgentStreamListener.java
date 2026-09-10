package io.github.git13166956007.dsh.agent;

public interface AgentStreamListener {
    void onText(String delta);

    default void onReasoning(String delta) {
    }

    void onToolCall(ToolCall call);

    void onToolResult(AgentTraceEvent result);
}
