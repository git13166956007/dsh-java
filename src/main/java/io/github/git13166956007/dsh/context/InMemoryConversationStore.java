package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryConversationStore implements ConversationStore {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>> conversations =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>>();
    private final ConcurrentHashMap<String, ConversationSummary> summaries =
            new ConcurrentHashMap<String, ConversationSummary>();
    private final ConcurrentHashMap<String, ConversationInfo> infos =
            new ConcurrentHashMap<String, ConversationInfo>();

    @Override
    public String open(String conversationId, String title) {
        String id = conversationId == null || conversationId.trim().isEmpty()
                ? UUID.randomUUID().toString() : conversationId.trim();
        String normalizedTitle = title == null || title.trim().isEmpty() ? "New conversation" : title.trim();
        Instant now = Instant.now();
        conversations.computeIfAbsent(id, ignored -> new CopyOnWriteArrayList<ChatMessage>());
        infos.computeIfAbsent(id, ignored -> new ConversationInfo(id, normalizedTitle, 0, now, now));
        return id;
    }

    @Override
    public List<ConversationInfo> list(int limit) {
        if (limit <= 0) return List.of();
        return infos.values().stream()
                .sorted(Comparator.comparing(ConversationInfo::updatedAt).reversed()
                        .thenComparing(ConversationInfo::id))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized void rename(String conversationId, String title) {
        ConversationInfo current = requireInfo(conversationId);
        infos.put(conversationId, new ConversationInfo(current.id(), title.trim(), current.messageCount(),
                current.createdAt(), Instant.now()));
    }

    @Override
    public synchronized boolean delete(String conversationId) {
        if (!infos.containsKey(conversationId)) return false;
        infos.remove(conversationId);
        conversations.remove(conversationId);
        summaries.remove(conversationId);
        return true;
    }

    @Override
    public List<ChatMessage> load(String conversationId, int limit) {
        List<ChatMessage> messages = conversations.get(conversationId);
        if (messages == null || limit <= 0) return List.of();
        int from = Math.max(0, messages.size() - limit);
        return new ArrayList<ChatMessage>(messages.subList(from, messages.size()));
    }

    @Override
    public synchronized void append(String conversationId, ChatMessage message) {
        conversations.computeIfAbsent(conversationId, ignored -> new CopyOnWriteArrayList<ChatMessage>())
                .add(message);
        ConversationInfo current = infos.get(conversationId);
        if (current == null) {
            Instant now = Instant.now();
            current = new ConversationInfo(conversationId, "New conversation", 0, now, now);
        }
        infos.put(conversationId, new ConversationInfo(current.id(), current.title(), current.messageCount() + 1,
                current.createdAt(), Instant.now()));
    }

    @Override
    public boolean exists(String conversationId) {
        return infos.containsKey(conversationId);
    }

    @Override
    public List<ConversationSearchResult> search(String query, int limit) {
        if (limit <= 0) return List.of();
        String normalized = query.toLowerCase(java.util.Locale.ROOT);
        List<ConversationSearchResult> result = new ArrayList<ConversationSearchResult>();
        for (ConversationInfo info : list(Integer.MAX_VALUE)) {
            List<ChatMessage> messages = conversations.getOrDefault(info.id(), new CopyOnWriteArrayList<ChatMessage>());
            for (int index = 0; index < messages.size() && result.size() < limit; index++) {
                String content = messages.get(index).content();
                if (content != null && content.toLowerCase(java.util.Locale.ROOT).contains(normalized)) {
                    result.add(new ConversationSearchResult(info.id(), index, messages.get(index).role().value(),
                            content, info.title()));
                }
            }
            if (result.size() >= limit) break;
        }
        return List.copyOf(result);
    }

    @Override
    public ConversationSummary loadSummary(String conversationId) {
        return summaries.get(conversationId);
    }

    @Override
    public void saveSummary(String conversationId, ConversationSummary summary) {
        summaries.put(conversationId, summary);
    }

    private ConversationInfo requireInfo(String conversationId) {
        ConversationInfo info = infos.get(conversationId);
        if (info == null) throw new IllegalArgumentException("unknown conversation: " + conversationId);
        return info;
    }
}
