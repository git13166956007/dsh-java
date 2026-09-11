package io.github.git13166956007.dsh.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class MariaDbRunStoreIntegrationTest {
    @Test
    void competingManagersCannotOverwriteTerminalRunState() throws Exception {
        String url = System.getenv().getOrDefault("DSH_DB_URL", "jdbc:mariadb://127.0.0.1:3307/dsh");
        String user = System.getenv().getOrDefault("DSH_DB_USER", "dsh");
        String password = System.getenv().getOrDefault("DSH_DB_PASSWORD", "dsh-local-password");
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            // Database is available; the test below is authoritative.
        } catch (Exception exception) {
            assumeTrue(false, "MariaDB integration test skipped: " + exception.getMessage());
            return;
        }

        RunManager first = new RunManager(new MariaDbRunStore(url, user, password));
        RunManager second = new RunManager(new MariaDbRunStore(url, user, password));
        String runId = first.start(new RunSpec(null, RunKind.AGENT, null, null, null, null, null));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> completed = executor.submit(() -> {
                try { first.complete(runId, "done"); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            });
            Future<?> cancelled = executor.submit(() -> {
                try { second.cancel(runId); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            });
            completed.get();
            cancelled.get();

            Run saved = first.find(runId);
            assertEquals(true, saved.status().terminal());
            assertEquals(2, first.events(runId).size());
        } finally {
            executor.shutdownNow();
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 PreparedStatement events = connection.prepareStatement("DELETE FROM dsh_run_event WHERE run_id=?");
                 PreparedStatement run = connection.prepareStatement("DELETE FROM dsh_run WHERE id=?")) {
                events.setString(1, runId);
                events.executeUpdate();
                run.setString(1, runId);
                run.executeUpdate();
            }
        }
    }
}
