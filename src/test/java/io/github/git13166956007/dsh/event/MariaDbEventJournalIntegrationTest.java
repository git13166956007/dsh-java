package io.github.git13166956007.dsh.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MariaDbEventJournalIntegrationTest {
    @Test
    void readsStablePagesFromTheDatabaseCursor() throws Exception {
        String url = System.getenv().getOrDefault("DSH_DB_URL", "jdbc:mariadb://127.0.0.1:3307/dsh");
        String user = System.getenv().getOrDefault("DSH_DB_USER", "dsh");
        String password = System.getenv().getOrDefault("DSH_DB_PASSWORD", "dsh-local-password");
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            // Database is available; the assertions below are authoritative.
        } catch (Exception exception) {
            assumeTrue(false, "MariaDB integration test skipped: " + exception.getMessage());
            return;
        }

        MariaDbEventJournal journal = new MariaDbEventJournal(url, user, password, new ObjectMapper());
        List<String> ids = new ArrayList<String>();
        try {
            long startCursor = journal.read(0, 100_000).nextCursor();
            for (int index = 0; index < 5; index++) {
                String id = UUID.randomUUID().toString();
                ids.add(id);
                journal.append(new EventRecord(id, "test.page", "value-" + index, null,
                        true, null, null, Instant.now()));
            }

            EventJournalPage first = journal.read(startCursor, 2);
            EventJournalPage second = journal.read(first.nextCursor(), 2);
            EventJournalPage third = journal.read(second.nextCursor(), 2);

            assertEquals(2, first.records().size());
            assertEquals(2, second.records().size());
            assertEquals(1, third.records().size());
            assertTrue(first.hasMore());
            assertTrue(second.hasMore());
            assertFalse(third.hasMore());
            assertEquals("value-0", first.records().get(0).payload().toString().replace("\"", ""));
            assertEquals("value-4", third.records().get(0).payload().toString().replace("\"", ""));
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM dsh_event_journal WHERE event_id=?")) {
                for (String id : ids) {
                    statement.setString(1, id);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }
    }
}
