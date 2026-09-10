package io.github.git13166956007.dsh.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModelResponse {
    private final String content;
    private final List<ToolCall> toolCalls;
    private final String finishReason;

    public ModelResponse(String content, List<ToolCall> toolCalls, String finishReason) {
        this.content = content;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<ToolCall>(toolCalls));
        this.finishReason = finishReason;
    }

    public String content() {
        return content;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public String finishReason() {
        return finishReason;
    }
}
