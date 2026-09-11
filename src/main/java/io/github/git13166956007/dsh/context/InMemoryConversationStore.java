package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.ObjectMapper;
import io.github.git13166956007.dsh.session.event.InMemorySessionEventLog;
import io.github.git13166956007.dsh.session.event.SessionEventCodec;
import io.github.git13166956007.dsh.session.event.SessionEventLog;
import io.github.git13166956007.dsh.session.event.SessionEventProjection;
import io.github.git13166956007.dsh.session.event.SessionEventTypes;

public final class InMemoryConversationStore implements ConversationStore {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SessionEventLog eventLog = new InMemorySessionEventLog();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>> conversations =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>>();
    private final ConcurrentHashMap<String, ConversationSummary> summaries =
            new ConcurrentHashMap<String, ConversationSummary>();
    private final ConcurrentHashMap<String, ConversationInfo> infos =
            new ConcurrentHashMap<String, ConversationInfo>();

    @Override
    public String open(String conversationId, String title) throws Exception {
        String id = conversationId == null || conversationId.trim().isEmpty()
                ? UUID.randomUUID().toString() : conversationId.trim();
        String normalizedTitle = title == null || title.trim().isEmpty() ? "New conversation" : title.trim();
        Instant now = Instant.now();
        conversations.computeIfAbsent(id, ignored -> new CopyOnWriteArrayList<ChatMessage>());
        if (infos.putIfAbsent(id, new ConversationInfo(id, normalizedTitle, 0, now, now)) == null) {
            eventLog.append(id, SessionEventTypes.CREATED, objectMapper.createObjectNode().put("title", normalizedTitle));
        }
        return id;
    }

    @Override
    public SessionEventLog eventLog() { return eventLog; }

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
    public synchronized void rename(String conversationId, String title) throws Exception {
        ConversationInfo current = requireInfo(conversationId);
        infos.put(conversationId, new ConversationInfo(current.id(), title.trim(), current.messageCount(),
                current.createdAt(), Instant.now()));
        eventLog.append(conversationId, SessionEventTypes.RENAMED,
                objectMapper.createObjectNode().put("title", title.trim()));
    }

    @Override
    public synchronized boolean delete(String conversationId) throws Exception {
        if (!infos.containsKey(conversationId)) return false;
        infos.remove(conversationId);
        conversations.remove(conversationId);
        summaries.remove(conversationId);
        eventLog.append(conversationId, SessionEventTypes.DELETED, objectMapper.createObjectNode());
        return true;
    }

    @Override
    public List<ChatMessage> load(String conversationId, int limit) throws Exception {
        List<ChatMessage> messages = SessionEventProjection.messages(eventLog.read(conversationId));
        if (messages.isEmpty()) messages = conversations.get(conversationId);
        if (messages == null || limit <= 0) return List.of();
        int from = Math.max(0, messages.size() - limit);
        return new ArrayList<ChatMessage>(messages.subList(from, messages.size()));
    }

    @Override
    public synchronized void append(String conversationId, ChatMessage message) throws Exception {
        conversations.computeIfAbsent(conversationId, ignored -> new CopyOnWriteArrayList<ChatMessage>())
                .add(message);
        String eventType = switch (message.role()) {
            case USER -> SessionEventTypes.USER_MESSAGE;
            case ASSISTANT -> SessionEventTypes.ASSISTANT_MESSAGE;
            case TOOL -> SessionEventTypes.TOOL_MESSAGE;
            case SYSTEM -> null;
        };
        if (eventType != null) eventLog.append(conversationId, eventType, SessionEventCodec.message(objectMapper, message));
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
    public void saveSummary(String conversationId, ConversationSummary summary) throws Exception {
        summaries.put(conversationId, summary);
        eventLog.append(conversationId, SessionEventTypes.SUMMARY_UPDATED,
                objectMapper.createObjectNode().put("content", summary.content())
                        .put("coveredMessageCount", summary.coveredMessageCount()));
    }

    private ConversationInfo requireInfo(String conversationId) {
        ConversationInfo info = infos.get(conversationId);
        if (info == null) throw new IllegalArgumentException("unknown conversation: " + conversationId);
        return info;
    }
}
