package io.github.git13166956007.dsh.model;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

public final class MariaDbModelUsageStore implements ModelUsageStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MariaDbModelUsageStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public ModelUsageData find(String modelId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT model_id, request_count, prompt_tokens, completion_tokens, total_tokens, "
                             + "estimated_cost_usd, last_used_at FROM dsh_model_usage WHERE model_id=?")) {
            statement.setString(1, modelId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                Timestamp lastUsedAt = rows.getTimestamp("last_used_at");
                return new ModelUsageData(rows.getString("model_id"), rows.getLong("request_count"),
                        rows.getLong("prompt_tokens"), rows.getLong("completion_tokens"), rows.getLong("total_tokens"),
                        rows.getDouble("estimated_cost_usd"), lastUsedAt == null ? null : lastUsedAt.toInstant());
            }
        }
    }

    @Override
    public void save(ModelUsageData usage) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_model_usage (model_id, request_count, prompt_tokens, completion_tokens, "
                             + "total_tokens, estimated_cost_usd, last_used_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE request_count=VALUES(request_count), "
                             + "prompt_tokens=VALUES(prompt_tokens), completion_tokens=VALUES(completion_tokens), "
                             + "total_tokens=VALUES(total_tokens), estimated_cost_usd=VALUES(estimated_cost_usd), "
                             + "last_used_at=VALUES(last_used_at)")) {
            statement.setString(1, usage.modelId());
            statement.setLong(2, usage.requestCount());
            statement.setLong(3, usage.promptTokens());
            statement.setLong(4, usage.completionTokens());
            statement.setLong(5, usage.totalTokens());
            statement.setDouble(6, usage.estimatedCostUsd());
            if (usage.lastUsedAt() == null) statement.setNull(7, java.sql.Types.TIMESTAMP);
            else statement.setTimestamp(7, Timestamp.from(usage.lastUsedAt()));
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(String modelId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM dsh_model_usage WHERE model_id=?")) {
            statement.setString(1, modelId);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_model_usage (model_id VARCHAR(64) NOT NULL PRIMARY KEY, "
                             + "request_count BIGINT NOT NULL DEFAULT 0, prompt_tokens BIGINT NOT NULL DEFAULT 0, "
                             + "completion_tokens BIGINT NOT NULL DEFAULT 0, total_tokens BIGINT NOT NULL DEFAULT 0, "
                             + "estimated_cost_usd DOUBLE NOT NULL DEFAULT 0, last_used_at TIMESTAMP(3) NULL) "
                             + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize model usage schema", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
