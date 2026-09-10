package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.List;
import java.util.UUID;

public final class ContextManager {
    private final ConversationStore store;
    private final int maxHistoryMessages;
    private final int maxContextTokens;

    public ContextManager(ConversationStore store, int maxHistoryMessages) {
        this(store, maxHistoryMessages, 12000);
    }

    public ContextManager(ConversationStore store, int maxHistoryMessages, int maxContextTokens) {
        if (maxHistoryMessages < 1) throw new IllegalArgumentException("maxHistoryMessages must be positive");
        if (maxContextTokens < 1) throw new IllegalArgumentException("maxContextTokens must be positive");
        this.store = store;
        this.maxHistoryMessages = maxHistoryMessages;
        this.maxContextTokens = maxContextTokens;
    }

    public String open(String conversationId, String title) throws Exception {
        String normalizedId = conversationId;
        if (conversationId != null && !conversationId.trim().isEmpty()) {
            try {
                normalizedId = UUID.fromString(conversationId.trim()).toString();
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("conversationId must be a UUID", exception);
            }
        }
        return store.open(normalizedId, title);
    }

    public List<ChatMessage> history(String conversationId) throws Exception {
        return history(conversationId, 0);
    }

    public List<ChatMessage> history(String conversationId, int modelContextWindow) throws Exception {
        return window(conversationId, modelContextWindow).messages();
    }

    public ContextWindow window(String conversationId) throws Exception {
        return window(conversationId, 0);
    }

    public ContextWindow window(String conversationId, int modelContextWindow) throws Exception {
        List<ChatMessage> loaded = store.load(conversationId, maxHistoryMessages);
        int contextTokens = modelContextWindow > 0 ? Math.min(maxContextTokens, modelContextWindow) : maxContextTokens;
        if (loaded.isEmpty()) return new ContextWindow(List.of(), 0, contextTokens, false);

        List<ChatMessage> selected = new java.util.ArrayList<ChatMessage>();
        int tokens = 0;
        boolean truncated = false;
        for (int index = loaded.size() - 1; index >= 0; index--) {
            ChatMessage message = loaded.get(index);
            int messageTokens = estimateTokens(message);
            if (!selected.isEmpty() && tokens + messageTokens > contextTokens) {
                truncated = true;
                break;
            }
            selected.add(0, message);
            tokens += messageTokens;
        }
        if (selected.size() < loaded.size()) truncated = true;
        return new ContextWindow(selected, tokens, contextTokens, truncated);
    }

    public void append(String conversationId, ChatMessage message) throws Exception {
        store.append(conversationId, message);
    }

    private static int estimateTokens(ChatMessage message) {
        int characters = message.content() == null ? 0 : message.content().codePointCount(0, message.content().length());
        for (io.github.git13166956007.dsh.agent.ToolCall call : message.toolCalls()) {
            characters += call.name() == null ? 0 : call.name().length();
            characters += call.arguments() == null ? 0 : call.arguments().toString().length();
        }
        return Math.max(1, (characters + 3) / 4);
    }
}
