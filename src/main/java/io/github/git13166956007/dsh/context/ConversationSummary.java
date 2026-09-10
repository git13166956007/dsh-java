package io.github.git13166956007.dsh.context;

public record ConversationSummary(String content, int coveredMessageCount) {
    public ConversationSummary {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("summary content must not be blank");
        if (coveredMessageCount < 0) throw new IllegalArgumentException("coveredMessageCount must not be negative");
    }
}
