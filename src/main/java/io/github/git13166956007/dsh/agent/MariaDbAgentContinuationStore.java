package io.github.git13166956007.dsh.agent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class MariaDbAgentContinuationStore implements AgentContinuationStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MariaDbAgentContinuationStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public String load(String runId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT payload FROM dsh_agent_continuation WHERE run_id=?")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    @Override
    public List<String> listRunIds() throws SQLException {
        List<String> result = new ArrayList<String>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT run_id FROM dsh_agent_continuation ORDER BY updated_at, run_id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    @Override
    public void save(String runId, String payload) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_agent_continuation (run_id, payload) VALUES (?, ?) "
                             + "ON DUPLICATE KEY UPDATE payload=VALUES(payload), updated_at=CURRENT_TIMESTAMP(3)")) {
            statement.setString(1, runId);
            statement.setString(2, payload);
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(String runId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM dsh_agent_continuation WHERE run_id=?")) {
            statement.setString(1, runId);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_agent_continuation ("
                             + "run_id CHAR(36) NOT NULL PRIMARY KEY, payload LONGTEXT NOT NULL, "
                             + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) "
                             + "ON UPDATE CURRENT_TIMESTAMP(3), CONSTRAINT fk_dsh_agent_continuation_run "
                             + "FOREIGN KEY (run_id) REFERENCES dsh_run(id) ON DELETE CASCADE) "
                             + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize agent continuation schema", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
