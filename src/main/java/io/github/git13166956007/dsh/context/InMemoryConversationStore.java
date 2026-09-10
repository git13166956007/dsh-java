package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryConversationStore implements ConversationStore {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>> conversations =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>>();
    private final ConcurrentHashMap<String, ConversationSummary> summaries =
            new ConcurrentHashMap<String, ConversationSummary>();

    @Override
    public String open(String conversationId, String title) {
        String id = conversationId == null || conversationId.trim().isEmpty()
                ? UUID.randomUUID().toString() : conversationId.trim();
        conversations.computeIfAbsent(id, ignored -> new CopyOnWriteArrayList<ChatMessage>());
        return id;
    }

    @Override
    public List<ChatMessage> load(String conversationId, int limit) {
        List<ChatMessage> messages = conversations.get(conversationId);
        if (messages == null || limit <= 0) return List.of();
        int from = Math.max(0, messages.size() - limit);
        return new ArrayList<ChatMessage>(messages.subList(from, messages.size()));
    }

    @Override
    public void append(String conversationId, ChatMessage message) {
        conversations.computeIfAbsent(conversationId, ignored -> new CopyOnWriteArrayList<ChatMessage>())
                .add(message);
    }

    @Override
    public boolean exists(String conversationId) {
        return conversations.containsKey(conversationId);
    }

    @Override
    public ConversationSummary loadSummary(String conversationId) {
        return summaries.get(conversationId);
    }

    @Override
    public void saveSummary(String conversationId, ConversationSummary summary) {
        summaries.put(conversationId, summary);
    }
}
