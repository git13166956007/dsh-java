package io.github.git13166956007.dsh.model;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

public final class MariaDbModelHealthStore implements ModelHealthStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MariaDbModelHealthStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public ModelHealthData find(String modelId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT model_id, status, success_count, failure_count, last_latency_ms, last_checked_at, "
                             + "last_success_at, last_error FROM dsh_model_health WHERE model_id=?")) {
            statement.setString(1, modelId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        }
    }

    @Override
    public void save(ModelHealthData health) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_model_health (model_id, status, success_count, failure_count, last_latency_ms, "
                             + "last_checked_at, last_success_at, last_error) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE status=VALUES(status), success_count=VALUES(success_count), "
                             + "failure_count=VALUES(failure_count), last_latency_ms=VALUES(last_latency_ms), "
                             + "last_checked_at=VALUES(last_checked_at), last_success_at=VALUES(last_success_at), "
                             + "last_error=VALUES(last_error)")) {
            statement.setString(1, health.modelId());
            statement.setString(2, health.status());
            statement.setLong(3, health.successCount());
            statement.setLong(4, health.failureCount());
            setLong(statement, 5, health.lastLatencyMs());
            setTimestamp(statement, 6, health.lastCheckedAt());
            setTimestamp(statement, 7, health.lastSuccessAt());
            statement.setString(8, health.lastError());
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(String modelId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM dsh_model_health WHERE model_id=?")) {
            statement.setString(1, modelId);
            statement.executeUpdate();
        }
    }

    @Override
    public void recordSuccess(String modelId, long latencyMs, Instant now) throws SQLException {
        upsertCounter(modelId, true, latencyMs, null, now);
    }

    @Override
    public void recordFailure(String modelId, long latencyMs, String error, Instant now) throws SQLException {
        upsertCounter(modelId, false, latencyMs, error, now);
    }

    private void upsertCounter(String modelId, boolean success, long latencyMs, String error, Instant now)
            throws SQLException {
        String sql = success
                ? "INSERT INTO dsh_model_health (model_id, status, success_count, failure_count, last_latency_ms, last_checked_at, last_success_at, last_error) VALUES (?, 'HEALTHY', 1, 0, ?, ?, ?, NULL) "
                    + "ON DUPLICATE KEY UPDATE status='HEALTHY', success_count=success_count+1, last_latency_ms=VALUES(last_latency_ms), last_checked_at=VALUES(last_checked_at), last_success_at=VALUES(last_success_at), last_error=NULL"
                : "INSERT INTO dsh_model_health (model_id, status, success_count, failure_count, last_latency_ms, last_checked_at, last_success_at, last_error) VALUES (?, 'UNHEALTHY', 0, 1, ?, ?, NULL, ?) "
                    + "ON DUPLICATE KEY UPDATE status='UNHEALTHY', failure_count=failure_count+1, last_latency_ms=VALUES(last_latency_ms), last_checked_at=VALUES(last_checked_at), last_error=VALUES(last_error)";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, modelId);
            statement.setLong(2, Math.max(0, latencyMs));
            statement.setTimestamp(3, Timestamp.from(now));
            if (success) statement.setTimestamp(4, Timestamp.from(now));
            else statement.setString(4, error);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_model_health (model_id VARCHAR(64) NOT NULL PRIMARY KEY, "
                             + "status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN', success_count BIGINT NOT NULL DEFAULT 0, "
                             + "failure_count BIGINT NOT NULL DEFAULT 0, last_latency_ms BIGINT NULL, "
                             + "last_checked_at TIMESTAMP(3) NULL, last_success_at TIMESTAMP(3) NULL, last_error TEXT NULL) "
                             + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize model health schema", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static ModelHealthData read(ResultSet rows) throws SQLException {
        return new ModelHealthData(rows.getString("model_id"), rows.getString("status"),
                rows.getLong("success_count"), rows.getLong("failure_count"), getLong(rows, "last_latency_ms"),
                instant(rows.getTimestamp("last_checked_at")), instant(rows.getTimestamp("last_success_at")),
                rows.getString("last_error"));
    }

    private static Long getLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.BIGINT);
        else statement.setLong(index, value);
    }

    private static void setTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.TIMESTAMP);
        else statement.setTimestamp(index, Timestamp.from(value));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
