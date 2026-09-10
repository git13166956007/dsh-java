package io.github.git13166956007.dsh.context;

import java.time.Instant;

public record ConversationInfo(String id, String title, int messageCount,
                               Instant createdAt, Instant updatedAt) {
    public ConversationInfo {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("conversation id must not be blank");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("conversation title must not be blank");
        if (messageCount < 0) throw new IllegalArgumentException("messageCount must not be negative");
        if (createdAt == null || updatedAt == null) throw new IllegalArgumentException("conversation timestamps are required");
    }
}
