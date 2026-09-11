package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.session.event.SessionEventLog;
import io.github.git13166956007.dsh.session.event.SessionEvent;
import io.github.git13166956007.dsh.session.event.SessionEventTypes;
import java.util.List;

public interface ConversationStore {
    default SessionEventLog eventLog() {
        return null;
    }

    String open(String conversationId, String title) throws Exception;

    default List<ConversationInfo> list(int limit) throws Exception {
        throw new UnsupportedOperationException("conversation listing is not supported");
    }

    default void rename(String conversationId, String title) throws Exception {
        throw new UnsupportedOperationException("conversation renaming is not supported");
    }

    default boolean delete(String conversationId) throws Exception {
        throw new UnsupportedOperationException("conversation deletion is not supported");
    }

    default List<ConversationSearchResult> search(String query, int limit) throws Exception {
        throw new UnsupportedOperationException("conversation search is not supported");
    }

    List<ChatMessage> load(String conversationId, int limit) throws Exception;

    void append(String conversationId, ChatMessage message) throws Exception;

    /** Appends a message with a caller-owned idempotency key when the transport has one. */
    default void append(String conversationId, String eventId, ChatMessage message) throws Exception {
        append(conversationId, message);
    }

    default boolean exists(String conversationId) throws Exception {
        return !load(conversationId, 1).isEmpty();
    }

    default List<ChatMessage> replay(String conversationId) throws Exception {
        return load(conversationId, Integer.MAX_VALUE);
    }

    default String fork(String conversationId, String title) throws Exception {
        if (!exists(conversationId)) throw new IllegalArgumentException("unknown conversation: " + conversationId);
        String forkedId = open(null, title);
        SessionEventLog sourceLog = eventLog();
        if (sourceLog != null) {
            List<SessionEvent> events = sourceLog.read(conversationId);
            int start = -1;
            for (int index = 0; index < events.size(); index++) {
                if (SessionEventTypes.CREATED.equals(events.get(index).type())) start = index;
            }
            if (start >= 0) {
                for (int index = start + 1; index < events.size(); index++) {
                    SessionEvent event = events.get(index);
                    if (!SessionEventTypes.DELETED.equals(event.type())
                            && !SessionEventTypes.RENAMED.equals(event.type())) {
                        sourceLog.append(forkedId, event.type(), event.payload());
                    }
                }
                return forkedId;
            }
        }
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

    /** Persists a summary only when it advances the covered message count. */
    default boolean saveSummaryIfNewer(String conversationId, ConversationSummary summary) throws Exception {
        ConversationSummary current = loadSummary(conversationId);
        if (current != null && current.coveredMessageCount() >= summary.coveredMessageCount()) return false;
        saveSummary(conversationId, summary);
        return true;
    }
}
