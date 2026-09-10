package io.github.git13166956007.dsh.agent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class MariaDbAgentProfileStore implements AgentProfileStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MariaDbAgentProfileStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public List<AgentProfileData> list() throws SQLException {
        List<AgentProfileData> result = new ArrayList<AgentProfileData>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, name, mode, model_id, system_prompt, max_turns, max_tool_calls, timeout_seconds, max_depth, enabled, active "
                             + "FROM dsh_agent_profile ORDER BY created_at, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new AgentProfileData(rows.getString("id"), rows.getString("name"),
                        AgentMode.parse(rows.getString("mode")), rows.getString("model_id"),
                        rows.getString("system_prompt"), rows.getInt("max_turns"),
                        rows.getInt("max_tool_calls"), rows.getInt("timeout_seconds"), rows.getInt("max_depth"),
                        rows.getBoolean("enabled"), rows.getBoolean("active")));
            }
        }
        return result;
    }

    @Override
    public void save(AgentProfileData profile) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_agent_profile "
                             + "(id, name, mode, model_id, system_prompt, max_turns, max_tool_calls, timeout_seconds, max_depth, enabled, active) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE name=VALUES(name), mode=VALUES(mode), "
                             + "model_id=VALUES(model_id), system_prompt=VALUES(system_prompt), "
                             + "max_turns=VALUES(max_turns), max_tool_calls=VALUES(max_tool_calls), "
                             + "timeout_seconds=VALUES(timeout_seconds), max_depth=VALUES(max_depth), "
                             + "enabled=VALUES(enabled), active=VALUES(active)")) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.name());
            statement.setString(3, profile.mode().value());
            statement.setString(4, profile.modelId());
            statement.setString(5, profile.systemPrompt());
            statement.setInt(6, profile.maxTurns());
            statement.setInt(7, profile.maxToolCalls());
            statement.setInt(8, profile.timeoutSeconds());
            statement.setInt(9, profile.maxDepth());
            statement.setBoolean(10, profile.enabled());
            statement.setBoolean(11, profile.active());
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(String id) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM dsh_agent_profile WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_agent_profile ("
                             + "id VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, "
                             + "mode VARCHAR(32) NOT NULL, model_id VARCHAR(64) NULL, "
                             + "system_prompt TEXT NULL, max_turns INT NOT NULL DEFAULT 8, "
                             + "max_tool_calls INT NOT NULL DEFAULT 64, timeout_seconds INT NOT NULL DEFAULT 300, "
                             + "max_depth INT NOT NULL DEFAULT 4, "
                             + "enabled BOOLEAN NOT NULL DEFAULT TRUE, active BOOLEAN NOT NULL DEFAULT FALSE, "
                             + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                             + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), "
                             + "INDEX idx_dsh_agent_active (active, enabled)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
            addColumn(connection, "max_tool_calls INT NOT NULL DEFAULT 64");
            addColumn(connection, "timeout_seconds INT NOT NULL DEFAULT 300");
            addColumn(connection, "max_depth INT NOT NULL DEFAULT 4");
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize agent profile schema", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static void addColumn(Connection connection, String definition) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "ALTER TABLE dsh_agent_profile ADD COLUMN IF NOT EXISTS " + definition)) {
            statement.executeUpdate();
        }
    }
}
