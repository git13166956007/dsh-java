package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.ObjectMapper;
import io.github.git13166956007.dsh.session.event.InMemorySessionEventLog;
import io.github.git13166956007.dsh.session.event.SessionEventCodec;
import io.github.git13166956007.dsh.session.event.SessionEventLog;
import io.github.git13166956007.dsh.session.event.SessionEventProjection;
import io.github.git13166956007.dsh.session.event.SessionEventTypes;

public final class InMemoryConversationStore implements ConversationStore {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SessionEventLog eventLog = new InMemorySessionEventLog();
    private final ConcurrentHashMap<String, ConversationInfo> infos =
            new ConcurrentHashMap<String, ConversationInfo>();

    @Override
    public String open(String conversationId, String title) throws Exception {
        String id = conversationId == null || conversationId.trim().isEmpty()
                ? UUID.randomUUID().toString() : conversationId.trim();
        String normalizedTitle = title == null || title.trim().isEmpty() ? "New conversation" : title.trim();
        Instant now = Instant.now();
        if (infos.putIfAbsent(id, new ConversationInfo(id, normalizedTitle, 0, now, now)) == null) {
            eventLog.append(id, SessionEventTypes.CREATED, objectMapper.createObjectNode().put("title", normalizedTitle));
        }
        return id;
    }

    @Override
    public SessionEventLog eventLog() { return eventLog; }

    @Override
    public List<ConversationInfo> list(int limit) throws Exception {
        if (limit <= 0) return List.of();
        List<ConversationInfo> projected = new ArrayList<ConversationInfo>();
        for (String conversationId : infos.keySet()) {
            ConversationInfo info = projectedInfo(conversationId);
            if (info != null) projected.add(info);
        }
        return projected.stream()
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
        if (!exists(conversationId)) return false;
        eventLog.append(conversationId, SessionEventTypes.DELETED, objectMapper.createObjectNode());
        return true;
    }

    @Override
    public List<ChatMessage> load(String conversationId, int limit) throws Exception {
        List<io.github.git13166956007.dsh.session.event.SessionEvent> events = eventLog.read(conversationId);
        List<ChatMessage> messages = SessionEventProjection.messages(events);
        if (limit <= 0) return List.of();
        int from = Math.max(0, messages.size() - limit);
        return new ArrayList<ChatMessage>(messages.subList(from, messages.size()));
    }

    @Override
    public synchronized void append(String conversationId, ChatMessage message) throws Exception {
        if (!exists(conversationId)) throw new IllegalArgumentException("unknown or deleted conversation: " + conversationId);
        String eventType = switch (message.role()) {
            case USER -> SessionEventTypes.USER_MESSAGE;
            case ASSISTANT -> SessionEventTypes.ASSISTANT_MESSAGE;
            case TOOL -> SessionEventTypes.TOOL_MESSAGE;
            case SYSTEM -> null;
        };
        if (eventType != null) eventLog.append(conversationId, eventType, SessionEventCodec.message(objectMapper, message));
        if (eventType == null) return;
        ConversationInfo current = infos.get(conversationId);
        infos.put(conversationId, new ConversationInfo(current.id(), current.title(), current.messageCount() + 1,
                current.createdAt(), Instant.now()));
    }

    @Override
    public boolean exists(String conversationId) {
        try { return projectedInfo(conversationId) != null; }
        catch (Exception exception) { throw new IllegalStateException("failed to project conversation", exception); }
    }

    @Override
    public List<ConversationSearchResult> search(String query, int limit) throws Exception {
        if (limit <= 0) return List.of();
        String normalized = query.toLowerCase(java.util.Locale.ROOT);
        List<ConversationSearchResult> result = new ArrayList<ConversationSearchResult>();
        for (ConversationInfo info : list(Integer.MAX_VALUE)) {
            List<ChatMessage> messages = load(info.id(), Integer.MAX_VALUE);
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
    public ConversationSummary loadSummary(String conversationId) throws Exception {
        List<io.github.git13166956007.dsh.session.event.SessionEvent> events = eventLog.read(conversationId);
        return SessionEventProjection.project(events).summary();
    }

    @Override
    public synchronized void saveSummary(String conversationId, ConversationSummary summary) throws Exception {
        if (!exists(conversationId)) throw new IllegalArgumentException("unknown or deleted conversation: " + conversationId);
        eventLog.append(conversationId, SessionEventTypes.SUMMARY_UPDATED,
                objectMapper.createObjectNode().put("content", summary.content())
                        .put("coveredMessageCount", summary.coveredMessageCount()));
    }

    @Override
    public synchronized boolean saveSummaryIfNewer(String conversationId, ConversationSummary summary) throws Exception {
        if (!exists(conversationId)) throw new IllegalArgumentException("unknown or deleted conversation: " + conversationId);
        ConversationSummary current = loadSummary(conversationId);
        if (current != null && current.coveredMessageCount() >= summary.coveredMessageCount()) return false;
        eventLog.append(conversationId, SessionEventTypes.SUMMARY_UPDATED,
                objectMapper.createObjectNode().put("content", summary.content())
                        .put("coveredMessageCount", summary.coveredMessageCount()));
        return true;
    }

    private ConversationInfo requireInfo(String conversationId) {
        ConversationInfo info = infos.get(conversationId);
        if (info == null) throw new IllegalArgumentException("unknown conversation: " + conversationId);
        try {
            if (SessionEventProjection.project(eventLog.read(conversationId)).deleted()) {
                throw new IllegalStateException("conversation is deleted: " + conversationId);
            }
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("failed to project conversation", exception);
        }
        return info;
    }

    private ConversationInfo projectedInfo(String conversationId) throws Exception {
        ConversationInfo fallback = infos.get(conversationId);
        if (fallback == null) return null;
        List<io.github.git13166956007.dsh.session.event.SessionEvent> events = eventLog.read(conversationId);
        SessionEventProjection.Snapshot snapshot = SessionEventProjection.project(events);
        if (snapshot.deleted()) return null;
        Instant createdAt = snapshot.createdAt() == null ? fallback.createdAt() : snapshot.createdAt();
        Instant updatedAt = snapshot.updatedAt() == null ? fallback.updatedAt() : snapshot.updatedAt();
        return new ConversationInfo(conversationId, snapshot.title(), snapshot.messages().size(), createdAt, updatedAt);
    }
}
