package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.List;
import java.util.UUID;

public final class ContextManager {
    private final ConversationStore store;
    private final int maxHistoryMessages;

    public ContextManager(ConversationStore store, int maxHistoryMessages) {
        if (maxHistoryMessages < 1) throw new IllegalArgumentException("maxHistoryMessages must be positive");
        this.store = store;
        this.maxHistoryMessages = maxHistoryMessages;
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
        return store.load(conversationId, maxHistoryMessages);
    }

    public void append(String conversationId, ChatMessage message) throws Exception {
        store.append(conversationId, message);
    }
}
