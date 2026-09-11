package io.github.git13166956007.dsh.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.agent.ToolCall;
import io.github.git13166956007.dsh.model.ModelTokenizer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InMemoryConversationStoreTest {
    @Test
    void keepsOnlyTheNewestHistoryWindow() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        String id = store.open(null, "test");
        store.append(id, ChatMessage.user("one"));
        store.append(id, ChatMessage.assistant("two", List.of()));
        store.append(id, ChatMessage.user("three"));

        assertEquals(List.of("two", "three"),
                store.load(id, 2).stream().map(ChatMessage::content).toList());
    }

    @Test
    void doesNotCountSystemMessagesAsConversationMessages() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        String id = store.open(null, "system");
        store.append(id, ChatMessage.system("internal context"));
        assertEquals(0, store.list(10).get(0).messageCount());
        assertEquals(List.of(), store.load(id, 10));
    }

    @Test
    void retainsAssistantToolCallsAndToolResultsAsConversationMessages() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        String id = store.open(null, "tool history");
        ObjectMapper mapper = new ObjectMapper();
        store.append(id, ChatMessage.assistant(null,
                List.of(new ToolCall("call-1", "demo_echo", mapper.createObjectNode().put("value", "hello")))));
        store.append(id, ChatMessage.tool("call-1", "echoed"));

        List<ChatMessage> messages = store.load(id, 10);

        assertEquals(2, messages.size());
        assertEquals("demo_echo", messages.get(0).toolCalls().get(0).name());
        assertEquals("hello", messages.get(0).toolCalls().get(0).arguments().path("value").asString());
        assertEquals("call-1", messages.get(1).toolCallId());
        assertEquals("echoed", messages.get(1).content());
    }

    @Test
    void contextWindowKeepsNewestMessagesWithinTokenBudget() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        ContextManager context = new ContextManager(store, 10, 4);
        String id = context.open(null, "budget");
        context.append(id, ChatMessage.user("1234567890"));
        context.append(id, ChatMessage.assistant("old", List.of()));
        context.append(id, ChatMessage.user("new"));

        ContextWindow window = context.window(id);
        assertEquals(2, window.messages().size());
        assertEquals("old", window.messages().get(0).content());
        assertEquals("new", window.messages().get(1).content());
        assertTrue(window.truncated());
        assertTrue(window.estimatedTokens() <= 4);
    }

    @Test
    void modelContextWindowNarrowsGlobalBudget() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        ContextManager context = new ContextManager(store, 10, 100);
        String id = context.open(null, "model budget");
        context.append(id, ChatMessage.user("1234567890"));
        context.append(id, ChatMessage.assistant("old", List.of()));
        context.append(id, ChatMessage.user("new"));

        ContextWindow window = context.window(id, 4);

        assertEquals(4, window.maxTokens());
        assertEquals(List.of("old", "new"), window.messages().stream().map(ChatMessage::content).toList());
    }

    @Test
    void compactsHistoryIntoAnIndependentRollingSummary() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        ContextManager context = new ContextManager(store, 10, 16);
        String id = context.open(null, "summary");
        context.append(id, ChatMessage.user("The release is Friday."));
        context.append(id, ChatMessage.assistant("Remember the migration checklist.", List.of()));
        context.append(id, ChatMessage.user("The database backup is required."));
        AtomicReference<String> prompt = new AtomicReference<String>();
        ChatModel summarizer = (messages, tools) -> {
            prompt.set(messages.get(1).content());
            return new ModelResponse("Release Friday; database backup required.",
                    List.of(), "stop");
        };

        assertEquals(true, context.compact(id, summarizer, null, null));
        ConversationSummary summary = store.loadSummary(id);
        assertEquals(3, summary.coveredMessageCount());
        assertTrue(prompt.get().contains("The release is Friday."));
        assertTrue(context.window(id).messages().get(0).content().startsWith("Conversation summary:"));
        assertEquals(false, context.compact(id, summarizer, null, null));

        context.append(id, ChatMessage.user("Use the staging environment first."));
        assertEquals(true, context.compact(id, summarizer, null, null));
        assertEquals(4, store.loadSummary(id).coveredMessageCount());
        assertTrue(prompt.get().contains("Existing conversation summary:"));
    }

    @Test
    void staleSummaryCannotOverwriteAnAlreadyAdvancedSummary() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        String id = store.open(null, "summary race");
        assertEquals(true, store.saveSummaryIfNewer(id, new ConversationSummary("new", 10)));
        assertEquals(false, store.saveSummaryIfNewer(id, new ConversationSummary("old", 4)));
        assertEquals("new", store.loadSummary(id).content());
        assertEquals(10, store.loadSummary(id).coveredMessageCount());
    }

    @Test
    void appendingWithTheSameEventIdDoesNotDuplicateTheMessage() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        String id = store.open(null, "idempotent");

        store.append(id, "message-event-1", ChatMessage.user("once"));
        store.append(id, "message-event-1", ChatMessage.user("once"));

        assertEquals(List.of("once"), store.replay(id).stream().map(ChatMessage::content).toList());
    }

    @Test
    void usesProviderTokenizerForContextBudget() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        ContextManager context = new ContextManager(store, 10, 100);
        String id = context.open(null, "provider tokenizer");
        context.append(id, ChatMessage.user("one"));
        context.append(id, ChatMessage.user("two"));

        ModelTokenizer tokenizer = message -> 60;
        ContextWindow window = context.window(id, 100, tokenizer);

        assertEquals(1, window.messages().size());
        assertEquals("two", window.messages().get(0).content());
        assertTrue(window.truncated());
    }

    @Test
    void collectsDynamicContextByPriorityWithinItsOwnBudgetAndIsolatesFailures() {
        ContextManager context = new ContextManager(new InMemoryConversationStore(), 10, 20, 3);
        AtomicBoolean called = new AtomicBoolean();
        AutoCloseable registration = context.registerProvider(new ContextProvider() {
            @Override
            public String id() {
                return "fixture";
            }

            @Override
            public List<ContextFragment> provide(ContextRequest request) {
                called.set("find release notes".equals(request.query()));
                return List.of(new ContextFragment("high", "important", 10),
                        new ContextFragment("low", "later", 1));
            }
        });
        context.registerProvider(new ContextProvider() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public List<ContextFragment> provide(ContextRequest request) {
                throw new IllegalStateException("fixture failure");
            }
        });

        ContextSnapshot snapshot = context.collect(
                new ContextRequest("conversation", "find release notes", "model", "agent", "execution"),
                message -> 2);

        assertTrue(called.get());
        assertEquals(List.of("high"), snapshot.fragments().stream().map(ContextFragment::title).toList());
        assertTrue(snapshot.truncated());
        assertEquals(List.of("broken: fixture failure"), snapshot.errors());
        assertTrue(snapshot.promptText().contains("important"));

        try {
            registration.close();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        assertEquals(List.of("broken"), context.providerIds());
    }

    @Test
    void forksReplaysAndSearchesAConversation() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        ContextManager context = new ContextManager(store, 10);
        String source = context.open(null, "source");
        context.append(source, ChatMessage.user("The release is Friday."));
        context.append(source, ChatMessage.assistant("Prepare the migration checklist.", List.of()));

        assertEquals(2, context.replay(source).size());
        assertEquals(List.of("Prepare the migration checklist."),
                context.search(source, "migration", 20).stream().map(ConversationSearchResult::content).toList());

        String fork = context.fork(source, "release fork");
        assertEquals(List.of("The release is Friday.", "Prepare the migration checklist."),
                context.replay(fork).stream().map(ChatMessage::content).toList());
        assertEquals("release fork", context.conversations(20).stream()
                .filter(info -> info.id().equals(fork)).findFirst().orElseThrow().title());
        assertEquals(1, store.eventLog().read(fork).stream()
                .filter(event -> event.type().equals(io.github.git13166956007.dsh.session.event.SessionEventTypes.CREATED))
                .count());
        assertEquals(1, store.eventLog().read(fork).stream()
                .filter(event -> event.type().equals(io.github.git13166956007.dsh.session.event.SessionEventTypes.USER_MESSAGE))
                .count());
    }

    @Test
    void listsRenamesSearchesAcrossAndDeletesConversations() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        ContextManager context = new ContextManager(store, 10);
        String first = context.open(null, "Release notes");
        context.append(first, ChatMessage.user("Prepare the migration checklist."));
        String second = context.open(null, "Design review");
        context.append(second, ChatMessage.user("Review the migration rollout."));

        assertEquals(2, context.conversations(20).size());
        assertEquals(2, context.searchAll("migration", 20).size());
        ConversationInfo renamed = context.rename(first, "Release planning");
        assertEquals("Release planning", renamed.title());
        assertTrue(context.delete(second));
        assertEquals(1, context.conversations(20).size());
        assertEquals(first, context.conversations(20).get(0).id());
    }

    @Test
    void derivesConversationMetadataFromSessionEvents() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        String id = store.open(null, "Initial title");
        store.append(id, ChatMessage.user("hello"));
        store.rename(id, "Renamed title");

        ConversationInfo info = store.list(10).get(0);
        assertEquals("Renamed title", info.title());
        assertEquals(1, info.messageCount());
        assertEquals(List.of("hello"), store.search("HELLO", 10).stream()
                .map(ConversationSearchResult::content).toList());
    }

    @Test
    void deletedSessionProjectionDoesNotFallBackToCachedMessages() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        String id = store.open(null, "Delete me");
        store.append(id, ChatMessage.user("must disappear"));
        store.delete(id);

        assertEquals(List.of(), store.load(id, 10));
        assertEquals(List.of(), store.search("must disappear", 10));
        assertEquals(1, store.eventLog().read(id).stream()
                .filter(event -> event.type().equals(io.github.git13166956007.dsh.session.event.SessionEventTypes.DELETED))
                .count());
    }
}
