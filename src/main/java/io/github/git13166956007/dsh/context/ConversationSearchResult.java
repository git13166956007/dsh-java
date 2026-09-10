package io.github.git13166956007.dsh.context;

public record ConversationSearchResult(String conversationId, int messageIndex, String role, String content,
                                       String conversationTitle) {
    public ConversationSearchResult(String conversationId, int messageIndex, String role, String content) {
        this(conversationId, messageIndex, role, content, null);
    }
}
