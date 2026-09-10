package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.List;

public interface ConversationStore {
    String open(String conversationId, String title) throws Exception;

    List<ChatMessage> load(String conversationId, int limit) throws Exception;

    void append(String conversationId, ChatMessage message) throws Exception;
}
