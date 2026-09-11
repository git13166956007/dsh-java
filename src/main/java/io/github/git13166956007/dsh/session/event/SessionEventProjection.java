package io.github.git13166956007.dsh.session.event;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.context.ConversationSummary;
import java.util.ArrayList;
import java.time.Instant;
import java.util.List;

public final class SessionEventProjection {
    private static final String DEFAULT_TITLE = "New conversation";

    private SessionEventProjection() { }

    public static List<ChatMessage> messages(List<SessionEvent> events) {
        return project(events).messages();
    }

    public static Snapshot project(List<SessionEvent> events) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        String title = DEFAULT_TITLE;
        ConversationSummary summary = null;
        Instant createdAt = null;
        Instant updatedAt = null;
        boolean deleted = false;
        if (events == null) return new Snapshot(title, messages, summary, null, null, false);
        for (SessionEvent event : events) {
            if (createdAt == null) createdAt = event.occurredAt();
            updatedAt = event.occurredAt();
            if (SessionEventTypes.CREATED.equals(event.type())) {
                title = event.payload().path("title").asString(DEFAULT_TITLE);
                messages.clear();
                summary = null;
                deleted = false;
            } else if (SessionEventTypes.RENAMED.equals(event.type())) {
                title = event.payload().path("title").asString(title);
            } else if (SessionEventTypes.DELETED.equals(event.type())) {
                messages.clear();
                summary = null;
                deleted = true;
            } else if (SessionEventTypes.USER_MESSAGE.equals(event.type())
                    || SessionEventTypes.ASSISTANT_MESSAGE.equals(event.type())
                    || SessionEventTypes.TOOL_MESSAGE.equals(event.type())) {
                if (!deleted) messages.add(SessionEventCodec.readMessage(event.payload()));
            } else if (SessionEventTypes.SUMMARY_UPDATED.equals(event.type()) && !deleted) {
                summary = new ConversationSummary(event.payload().path("content").asString(""),
                        event.payload().path("coveredMessageCount").asInt(0));
            }
        }
        return new Snapshot(title, messages, summary, createdAt, updatedAt, deleted);
    }

    public record Snapshot(String title, List<ChatMessage> messages, ConversationSummary summary,
                           Instant createdAt, Instant updatedAt, boolean deleted) {
        public Snapshot {
            title = title == null || title.isBlank() ? DEFAULT_TITLE : title;
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }
}
