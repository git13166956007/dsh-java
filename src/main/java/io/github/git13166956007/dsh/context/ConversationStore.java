package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.List;

public interface ConversationStore {
    String open(String conversationId, String title) throws Exception;

    List<ChatMessage> load(String conversationId, int limit) throws Exception;

    void append(String conversationId, ChatMessage message) throws Exception;

    default ConversationSummary loadSummary(String conversationId) throws Exception {
        return null;
    }

    default void saveSummary(String conversationId, ConversationSummary summary) throws Exception {
        throw new UnsupportedOperationException("conversation summaries are not supported");
    }
}
