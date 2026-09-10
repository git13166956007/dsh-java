package io.github.git13166956007.dsh.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChatMessage {
    public enum Role {
        SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool");

        private final String value;

        Role(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private final Role role;
    private final String content;
    private final String reasoningContent;
    private final String toolCallId;
    private final List<ToolCall> toolCalls;

    private ChatMessage(Role role, String content, String reasoningContent, String toolCallId,
                        List<ToolCall> toolCalls) {
        this.role = role;
        this.content = content;
        this.reasoningContent = reasoningContent;
        this.toolCallId = toolCallId;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<ToolCall>(toolCalls));
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null, null, Collections.<ToolCall>emptyList());
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null, null, Collections.<ToolCall>emptyList());
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return assistant(content, toolCalls, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls, String reasoningContent) {
        return new ChatMessage(Role.ASSISTANT, content, reasoningContent, null, toolCalls);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, null, toolCallId, Collections.<ToolCall>emptyList());
    }

    public Role role() {
        return role;
    }

    public String content() {
        return content;
    }

    public String reasoningContent() {
        return reasoningContent;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }
}
