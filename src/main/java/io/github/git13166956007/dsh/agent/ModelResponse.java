package io.github.git13166956007.dsh.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModelResponse {
    private final String content;
    private final List<ToolCall> toolCalls;
    private final String finishReason;
    private final Integer promptTokens;
    private final Integer completionTokens;
    private final Integer totalTokens;

    public ModelResponse(String content, List<ToolCall> toolCalls, String finishReason) {
        this(content, toolCalls, finishReason, null, null, null);
    }

    public ModelResponse(String content, List<ToolCall> toolCalls, String finishReason,
                         Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this.content = content;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<ToolCall>(toolCalls));
        this.finishReason = finishReason;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
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

    public Integer promptTokens() {
        return promptTokens;
    }

    public Integer completionTokens() {
        return completionTokens;
    }

    public Integer totalTokens() {
        return totalTokens;
    }
}
