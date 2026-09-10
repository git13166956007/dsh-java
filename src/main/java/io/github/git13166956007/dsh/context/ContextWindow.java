package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ContextWindow(List<ChatMessage> messages, int estimatedTokens, int maxTokens, boolean truncated) {
    public ContextWindow {
        messages = Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
    }
}
