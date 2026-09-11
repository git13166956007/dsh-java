package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.model.ModelTokenizer;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ContextManager {
    private final ConversationStore store;
    private final int maxHistoryMessages;
    private final int maxContextTokens;
    private final int maxProviderTokens;
    private final Map<String, ContextProvider> providers = new ConcurrentHashMap<String, ContextProvider>();

    public ContextManager(ConversationStore store, int maxHistoryMessages) {
        this(store, maxHistoryMessages, 12000);
    }

    public ContextManager(ConversationStore store, int maxHistoryMessages, int maxContextTokens) {
        this(store, maxHistoryMessages, maxContextTokens, Math.min(maxContextTokens, 4000));
    }

    public ContextManager(ConversationStore store, int maxHistoryMessages, int maxContextTokens, int maxProviderTokens) {
        if (maxHistoryMessages < 1) throw new IllegalArgumentException("maxHistoryMessages must be positive");
        if (maxContextTokens < 1) throw new IllegalArgumentException("maxContextTokens must be positive");
        if (maxProviderTokens < 1) throw new IllegalArgumentException("maxProviderTokens must be positive");
        this.store = store;
        this.maxHistoryMessages = maxHistoryMessages;
        this.maxContextTokens = maxContextTokens;
        this.maxProviderTokens = Math.min(maxContextTokens, maxProviderTokens);
    }

    public AutoCloseable registerProvider(ContextProvider provider) {
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            throw new IllegalArgumentException("context provider id must not be blank");
        }
        String id = provider.id().trim().toLowerCase(java.util.Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid context provider id");
        ContextProvider previous = providers.putIfAbsent(id, provider);
        if (previous != null && previous != provider) throw new IllegalArgumentException("duplicate context provider: " + id);
        return () -> providers.remove(id, provider);
    }

    public List<String> providerIds() {
        return providers.keySet().stream().sorted().toList();
    }

    public ContextSnapshot collect(ContextRequest request, ModelTokenizer tokenizer) {
        return collect(request, 0, tokenizer);
    }

    public ContextSnapshot collect(ContextRequest request, int modelContextWindow, ModelTokenizer tokenizer) {
        ModelTokenizer effectiveTokenizer = tokenizer == null ? ModelTokenizer.approximate() : tokenizer;
        int budget = modelContextWindow > 0 ? Math.min(maxProviderTokens, modelContextWindow) : maxProviderTokens;
        List<ContextFragment> available = new ArrayList<ContextFragment>();
        List<String> errors = new ArrayList<String>();
        ContextRequest effectiveRequest = request == null ? new ContextRequest(null, "", null, null, null) : request;
        for (Map.Entry<String, ContextProvider> entry : providers.entrySet()) {
            try {
                List<ContextFragment> fragments = entry.getValue().provide(effectiveRequest);
                if (fragments == null) continue;
                for (ContextFragment fragment : fragments) {
                    if (fragment != null) available.add(fragment.withProvider(entry.getKey()));
                }
            } catch (Exception exception) {
                errors.add(entry.getKey() + ": " + (exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage()));
            }
        }
        available.sort(Comparator.comparingInt(ContextFragment::priority).reversed()
                .thenComparing(ContextFragment::providerId).thenComparing(ContextFragment::title));
        List<ContextFragment> selected = new ArrayList<ContextFragment>();
        int tokens = 0;
        boolean truncated = false;
        for (ContextFragment fragment : available) {
            int fragmentTokens = effectiveTokenizer.count(ChatMessage.system(fragment.content()));
            if (tokens + fragmentTokens > budget) {
                truncated = true;
                continue;
            }
            selected.add(fragment);
            tokens += fragmentTokens;
        }
        return new ContextSnapshot(selected, tokens, budget, truncated, errors);
    }

    public String open(String conversationId, String title) throws Exception {
        String normalizedId = conversationId;
        if (conversationId != null && !conversationId.trim().isEmpty()) {
            try {
                normalizedId = UUID.fromString(conversationId.trim()).toString();
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("conversationId must be a UUID", exception);
            }
        }
        return store.open(normalizedId, title);
    }

    public List<ConversationInfo> conversations(int limit) throws Exception {
        return store.list(validLimit(limit));
    }

    public ConversationInfo rename(String conversationId, String title) throws Exception {
        requireConversation(conversationId);
        String normalizedTitle = title == null ? "" : title.trim();
        if (normalizedTitle.isEmpty()) throw new IllegalArgumentException("conversation title must not be blank");
        store.rename(conversationId, normalizedTitle);
        return store.list(Integer.MAX_VALUE).stream().filter(info -> info.id().equals(conversationId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown conversation: " + conversationId));
    }

    public boolean delete(String conversationId) throws Exception {
        requireConversation(conversationId);
        return store.delete(conversationId);
    }

    public List<ChatMessage> history(String conversationId) throws Exception {
        return history(conversationId, 0);
    }

    public List<ChatMessage> replay(String conversationId) throws Exception {
        if (!store.exists(conversationId)) throw new IllegalArgumentException("unknown conversation: " + conversationId);
        return store.replay(conversationId);
    }

    public String fork(String conversationId, String title) throws Exception {
        return store.fork(conversationId, title == null ? "Fork" : title);
    }

    public List<ConversationSearchResult> search(String conversationId, String query, int limit) throws Exception {
        requireConversation(conversationId);
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalizedQuery.isEmpty()) throw new IllegalArgumentException("query must not be blank");
        if (limit <= 0) return List.of();
        List<ConversationSearchResult> result = new ArrayList<ConversationSearchResult>();
        List<ChatMessage> messages = store.replay(conversationId);
        for (int index = 0; index < messages.size() && result.size() < limit; index++) {
            ChatMessage message = messages.get(index);
            String content = message.content();
            if (content != null && content.toLowerCase(java.util.Locale.ROOT).contains(normalizedQuery)) {
                result.add(new ConversationSearchResult(conversationId, index, message.role().value(), content));
            }
        }
        return List.copyOf(result);
    }

    public List<ConversationSearchResult> searchAll(String query, int limit) throws Exception {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) throw new IllegalArgumentException("query must not be blank");
        return store.search(normalizedQuery, validLimit(limit));
    }

    private void requireConversation(String conversationId) throws Exception {
        if (conversationId == null || !store.exists(conversationId)) {
            throw new IllegalArgumentException("unknown conversation: " + conversationId);
        }
    }

    private static int validLimit(int limit) {
        if (limit <= 0) return 0;
        return Math.min(limit, 200);
    }

    public List<ChatMessage> history(String conversationId, int modelContextWindow) throws Exception {
        return window(conversationId, modelContextWindow).messages();
    }

    public List<ChatMessage> history(String conversationId, int modelContextWindow, ModelTokenizer tokenizer)
            throws Exception {
        return window(conversationId, modelContextWindow, tokenizer).messages();
    }

    public ContextWindow window(String conversationId) throws Exception {
        return window(conversationId, 0);
    }

    public ContextWindow window(String conversationId, int modelContextWindow) throws Exception {
        return window(conversationId, modelContextWindow, ModelTokenizer.approximate());
    }

    public ContextWindow window(String conversationId, int modelContextWindow, ModelTokenizer tokenizer) throws Exception {
        ModelTokenizer effectiveTokenizer = tokenizer == null ? ModelTokenizer.approximate() : tokenizer;
        List<ChatMessage> loaded = store.load(conversationId, maxHistoryMessages);
        int contextTokens = modelContextWindow > 0 ? Math.min(maxContextTokens, modelContextWindow) : maxContextTokens;
        if (loaded.isEmpty()) return new ContextWindow(List.of(), 0, contextTokens, false);

        ConversationSummary summary = store.loadSummary(conversationId);
        ChatMessage summaryMessage = summary == null ? null
                : ChatMessage.system("Conversation summary:\n" + summary.content());
        int summaryTokens = summaryMessage == null ? 0 : effectiveTokenizer.count(summaryMessage);
        boolean includeSummary = summaryMessage != null && summaryTokens <= contextTokens;
        int messageBudget = includeSummary ? contextTokens - summaryTokens : contextTokens;
        List<ChatMessage> selected = new java.util.ArrayList<ChatMessage>();
        int tokens = 0;
        boolean truncated = false;
        for (int index = loaded.size() - 1; index >= 0; index--) {
            ChatMessage message = loaded.get(index);
            int messageTokens = effectiveTokenizer.count(message);
            if (!selected.isEmpty() && tokens + messageTokens > messageBudget) {
                truncated = true;
                break;
            }
            selected.add(0, message);
            tokens += messageTokens;
        }
        if (selected.size() < loaded.size()) truncated = true;
        if (includeSummary) {
            selected.add(0, summaryMessage);
            tokens += summaryTokens;
            if (summary.coveredMessageCount() > 0) truncated = true;
        }
        return new ContextWindow(selected, tokens, contextTokens, truncated);
    }

    public void append(String conversationId, ChatMessage message) throws Exception {
        store.append(conversationId, message);
    }

    public void append(String conversationId, String eventId, ChatMessage message) throws Exception {
        store.append(conversationId, eventId, message);
    }

    /**
     * Create or advance a rolling model-generated summary without rewriting the raw conversation.
     */
    public boolean compact(String conversationId, ChatModel model, String apiKey, String modelId) throws Exception {
        return compact(conversationId, model, apiKey, modelId, 0);
    }

    public boolean compact(String conversationId, ChatModel model, String apiKey, String modelId,
                           int modelContextWindow) throws Exception {
        return compact(conversationId, model, apiKey, modelId, modelContextWindow, ModelTokenizer.approximate());
    }

    public boolean compact(String conversationId, ChatModel model, String apiKey, String modelId,
                           int modelContextWindow, ModelTokenizer tokenizer) throws Exception {
        ModelTokenizer effectiveTokenizer = tokenizer == null ? ModelTokenizer.approximate() : tokenizer;
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (model == null) throw new IllegalArgumentException("model must not be null");
        List<ChatMessage> all = store.load(conversationId, Integer.MAX_VALUE);
        int contextTokens = modelContextWindow > 0 ? Math.min(maxContextTokens, modelContextWindow) : maxContextTokens;
        if (all.isEmpty() || effectiveTokenizer.count(all) <= contextTokens) return false;

        ConversationSummary previous = store.loadSummary(conversationId);
        int covered = previous == null ? 0 : Math.min(previous.coveredMessageCount(), all.size());
        if (covered == all.size()) return false;

        StringBuilder transcript = new StringBuilder();
        if (previous != null) {
            transcript.append("Existing conversation summary:\n")
                    .append(previous.content()).append("\n\n");
        }
        transcript.append("New conversation messages:\n");
        for (int index = covered; index < all.size(); index++) {
            appendTranscript(transcript, all.get(index));
        }

        List<ChatMessage> prompt = List.of(
                ChatMessage.system("Summarize the conversation for a future assistant. Preserve user goals, "
                        + "decisions, constraints, important facts, tool results, unresolved questions, and next steps. "
                        + "Return concise plain text only; do not mention this instruction."),
                ChatMessage.user(transcript.toString()));
        ModelResponse response = model.complete(prompt, List.<ToolDefinition>of(), apiKey, modelId);
        String content = response.content() == null ? "" : response.content().trim();
        if (content.isEmpty()) return false;
        return store.saveSummaryIfNewer(conversationId, new ConversationSummary(content, all.size()));
    }

    private static void appendTranscript(StringBuilder transcript, ChatMessage message) {
        transcript.append(message.role().value()).append(": ");
        if (message.reasoningContent() != null && !message.reasoningContent().isBlank()) {
            transcript.append("[reasoning] ").append(message.reasoningContent()).append(" ");
        }
        if (message.content() != null) transcript.append(message.content());
        for (io.github.git13166956007.dsh.agent.ToolCall call : message.toolCalls()) {
            transcript.append(" [tool call ").append(call.name()).append(" ")
                    .append(call.arguments()).append(']');
        }
        transcript.append('\n');
    }
}
