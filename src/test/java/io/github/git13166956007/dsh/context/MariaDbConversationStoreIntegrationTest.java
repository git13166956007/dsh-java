package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.session.event.SessionEventTypes;
import io.github.git13166956007.dsh.session.event.MariaDbSessionEventLog;
import io.github.git13166956007.dsh.session.event.SessionEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MariaDbConversationStoreIntegrationTest {
    @Test
    void persistsAndReplaysOnlySessionEvents() throws Exception {
        String url = System.getenv().getOrDefault("DSH_DB_URL", "jdbc:mariadb://127.0.0.1:3307/dsh");
        String user = System.getenv().getOrDefault("DSH_DB_USER", "dsh");
        String password = System.getenv().getOrDefault("DSH_DB_PASSWORD", "dsh-local-password");
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            // Database is available; the test below is authoritative.
        } catch (Exception exception) {
            assumeTrue(false, "MariaDB integration test skipped: " + exception.getMessage());
            return;
        }

        String id = UUID.randomUUID().toString();
        try {
            MariaDbConversationStore store = new MariaDbConversationStore(url, user, password);
            store.open(id, "event replay");
            store.append(id, ChatMessage.user("persisted message"));

            assertTrue(store.exists(id));
            assertEquals(List.of("persisted message"), store.load(id, 10).stream()
                    .map(ChatMessage::content).toList());

            assertTrue(store.delete(id));
            assertFalse(store.exists(id));
            assertEquals(List.of(), store.load(id, 10));
            assertFalse(store.delete(id));

            List<String> eventTypes = store.eventLog().read(id).stream().map(event -> event.type()).toList();
            assertEquals(List.of(SessionEventTypes.CREATED, SessionEventTypes.USER_MESSAGE,
                    SessionEventTypes.DELETED), eventTypes);
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT COUNT(*) FROM dsh_conversation WHERE id=?")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    assertEquals(1, rows.getInt(1));
                }
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 PreparedStatement events = connection.prepareStatement("DELETE FROM dsh_session_event WHERE session_id=?");
                 PreparedStatement conversation = connection.prepareStatement("DELETE FROM dsh_conversation WHERE id=?")) {
                events.setString(1, id);
                events.executeUpdate();
                conversation.setString(1, id);
                conversation.executeUpdate();
            }
        }
    }

    @Test
    void allocatesEventSequencesAcrossStoreInstances() throws Exception {
        String url = System.getenv().getOrDefault("DSH_DB_URL", "jdbc:mariadb://127.0.0.1:3307/dsh");
        String user = System.getenv().getOrDefault("DSH_DB_USER", "dsh");
        String password = System.getenv().getOrDefault("DSH_DB_PASSWORD", "dsh-local-password");
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            // Database is available; the test below is authoritative.
        } catch (Exception exception) {
            assumeTrue(false, "MariaDB integration test skipped: " + exception.getMessage());
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> tasks = new java.util.ArrayList<Future<?>>();
        try {
            MariaDbSessionEventLog first = new MariaDbSessionEventLog(url, user, password, new ObjectMapper());
            MariaDbSessionEventLog second = new MariaDbSessionEventLog(url, user, password, new ObjectMapper());
            for (int index = 0; index < 96; index++) {
                MariaDbSessionEventLog log = index % 2 == 0 ? first : second;
                tasks.add(executor.submit(() -> log.append(sessionId, SessionEventTypes.USER_MESSAGE,
                        new ObjectMapper().createObjectNode().put("value", "event"))));
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        for (Future<?> task : tasks) task.get();

        try {
            MariaDbSessionEventLog log = new MariaDbSessionEventLog(url, user, password, new ObjectMapper());
            assertEquals(96, log.read(sessionId).size());
            assertEquals(java.util.stream.LongStream.rangeClosed(1, 96).boxed().toList(),
                    log.read(sessionId).stream().map(event -> event.sequence()).toList());
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 PreparedStatement events = connection.prepareStatement("DELETE FROM dsh_session_event WHERE session_id=?");
                 PreparedStatement head = connection.prepareStatement("DELETE FROM dsh_session_event_head WHERE session_id=?")) {
                events.setString(1, sessionId);
                events.executeUpdate();
                head.setString(1, sessionId);
                head.executeUpdate();
            }
        }
    }

    @Test
    void retriesTheSameEventIdWithoutAppendingTwice() throws Exception {
        String url = System.getenv().getOrDefault("DSH_DB_URL", "jdbc:mariadb://127.0.0.1:3307/dsh");
        String user = System.getenv().getOrDefault("DSH_DB_USER", "dsh");
        String password = System.getenv().getOrDefault("DSH_DB_PASSWORD", "dsh-local-password");
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            // Database is available; the test below is authoritative.
        } catch (Exception exception) {
            assumeTrue(false, "MariaDB integration test skipped: " + exception.getMessage());
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        MariaDbSessionEventLog log = new MariaDbSessionEventLog(url, user, password, new ObjectMapper());
        try {
            var payload = new ObjectMapper().createObjectNode().put("value", "once");
            SessionEvent first = log.append(sessionId, eventId, SessionEventTypes.USER_MESSAGE, payload);
            SessionEvent retry = log.append(sessionId, eventId, SessionEventTypes.USER_MESSAGE,
                    new ObjectMapper().createObjectNode().put("value", "once"));
            assertEquals(first, retry);
            assertEquals(1, log.read(sessionId).size());
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 PreparedStatement events = connection.prepareStatement("DELETE FROM dsh_session_event WHERE session_id=?");
                 PreparedStatement head = connection.prepareStatement("DELETE FROM dsh_session_event_head WHERE session_id=?")) {
                events.setString(1, sessionId);
                events.executeUpdate();
                head.setString(1, sessionId);
                head.executeUpdate();
            }
        }
    }

    @Test
    void summaryWriteOnlyAdvancesTheCoveredMessageCount() throws Exception {
        String url = System.getenv().getOrDefault("DSH_DB_URL", "jdbc:mariadb://127.0.0.1:3307/dsh");
        String user = System.getenv().getOrDefault("DSH_DB_USER", "dsh");
        String password = System.getenv().getOrDefault("DSH_DB_PASSWORD", "dsh-local-password");
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            // Database is available; the test below is authoritative.
        } catch (Exception exception) {
            assumeTrue(false, "MariaDB integration test skipped: " + exception.getMessage());
            return;
        }

        String conversationId = UUID.randomUUID().toString();
        MariaDbConversationStore store = new MariaDbConversationStore(url, user, password);
        try {
            store.open(conversationId, "summary race");
            assertTrue(store.saveSummaryIfNewer(conversationId, new ConversationSummary("new", 10)));
            assertFalse(store.saveSummaryIfNewer(conversationId, new ConversationSummary("old", 4)));
            assertEquals("new", store.loadSummary(conversationId).content());
            assertEquals(10, store.loadSummary(conversationId).coveredMessageCount());
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 PreparedStatement events = connection.prepareStatement("DELETE FROM dsh_session_event WHERE session_id=?");
                 PreparedStatement head = connection.prepareStatement("DELETE FROM dsh_session_event_head WHERE session_id=?");
                 PreparedStatement conversation = connection.prepareStatement("DELETE FROM dsh_conversation WHERE id=?")) {
                events.setString(1, conversationId);
                events.executeUpdate();
                head.setString(1, conversationId);
                head.executeUpdate();
                conversation.setString(1, conversationId);
                conversation.executeUpdate();
            }
        }
    }
}
