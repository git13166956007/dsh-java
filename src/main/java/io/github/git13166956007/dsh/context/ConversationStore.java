package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.List;

public interface ConversationStore {
    String open(String conversationId, String title) throws Exception;

    List<ChatMessage> load(String conversationId, int limit) throws Exception;

    void append(String conversationId, ChatMessage message) throws Exception;

    default boolean exists(String conversationId) throws Exception {
        return !load(conversationId, 1).isEmpty();
    }

    default List<ChatMessage> replay(String conversationId) throws Exception {
        return load(conversationId, Integer.MAX_VALUE);
    }

    default String fork(String conversationId, String title) throws Exception {
        if (!exists(conversationId)) throw new IllegalArgumentException("unknown conversation: " + conversationId);
        String forkedId = open(null, title);
        for (ChatMessage message : replay(conversationId)) append(forkedId, message);
        ConversationSummary summary = loadSummary(conversationId);
        if (summary != null) saveSummary(forkedId, summary);
        return forkedId;
    }

    default ConversationSummary loadSummary(String conversationId) throws Exception {
        return null;
    }

    default void saveSummary(String conversationId, ConversationSummary summary) throws Exception {
        throw new UnsupportedOperationException("conversation summaries are not supported");
    }
}
