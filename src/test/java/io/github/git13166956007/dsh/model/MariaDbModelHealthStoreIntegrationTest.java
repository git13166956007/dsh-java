package io.github.git13166956007.dsh.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MariaDbModelHealthStoreIntegrationTest {
    @Test
    void atomicallyAccumulatesHealthCountersAcrossStoreInstances() throws Exception {
        String url = System.getenv().getOrDefault("DSH_DB_URL", "jdbc:mariadb://127.0.0.1:3307/dsh");
        String user = System.getenv().getOrDefault("DSH_DB_USER", "dsh");
        String password = System.getenv().getOrDefault("DSH_DB_PASSWORD", "dsh-local-password");
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            // Database is available; the test below is authoritative.
        } catch (Exception exception) {
            assumeTrue(false, "MariaDB integration test skipped: " + exception.getMessage());
            return;
        }

        String modelId = "health-test-" + UUID.randomUUID();
        MariaDbModelHealthStore first = new MariaDbModelHealthStore(url, user, password);
        MariaDbModelHealthStore second = new MariaDbModelHealthStore(url, user, password);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> tasks = new ArrayList<Future<?>>();
        try {
            for (int index = 0; index < 96; index++) {
                MariaDbModelHealthStore store = index % 2 == 0 ? first : second;
                tasks.add(executor.submit(() -> {
                    try { store.recordSuccess(modelId, 4, Instant.now()); }
                    catch (Exception exception) { throw new RuntimeException(exception); }
                }));
            }
            for (int index = 0; index < 64; index++) {
                MariaDbModelHealthStore store = index % 2 == 0 ? first : second;
                tasks.add(executor.submit(() -> {
                    try { store.recordFailure(modelId, 7, "temporary", Instant.now()); }
                    catch (Exception exception) { throw new RuntimeException(exception); }
                }));
            }
            for (Future<?> task : tasks) task.get();
            ModelHealthData health = first.find(modelId);
            assertEquals(96, health.successCount());
            assertEquals(64, health.failureCount());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM dsh_model_health WHERE model_id=?")) {
                statement.setString(1, modelId);
                statement.executeUpdate();
            }
        }
    }
}
