package io.github.git13166956007.dsh.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
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
}
