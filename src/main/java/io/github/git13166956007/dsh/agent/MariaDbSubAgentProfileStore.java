package io.github.git13166956007.dsh.agent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MariaDbSubAgentProfileStore implements SubAgentProfileStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MariaDbSubAgentProfileStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public List<SubAgentProfileData> list() throws SQLException {
        List<SubAgentProfileData> result = new ArrayList<SubAgentProfileData>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, name, mode, model_id, system_prompt, max_turns, allowed_tools, skill_ids, enabled "
                             + "FROM dsh_sub_agent_profile ORDER BY created_at, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(new SubAgentProfileData(rows.getString("id"), rows.getString("name"),
                    AgentMode.parse(rows.getString("mode")), rows.getString("model_id"), rows.getString("system_prompt"),
                    rows.getInt("max_turns"), split(rows.getString("allowed_tools")), split(rows.getString("skill_ids")),
                    rows.getBoolean("enabled")));
        }
        return result;
    }

    @Override
    public void save(SubAgentProfileData profile) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_sub_agent_profile "
                             + "(id, name, mode, model_id, system_prompt, max_turns, allowed_tools, skill_ids, enabled) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name=VALUES(name), "
                             + "mode=VALUES(mode), model_id=VALUES(model_id), system_prompt=VALUES(system_prompt), "
                             + "max_turns=VALUES(max_turns), allowed_tools=VALUES(allowed_tools), skill_ids=VALUES(skill_ids), "
                             + "enabled=VALUES(enabled)")) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.name());
            statement.setString(3, profile.mode().value());
            statement.setString(4, profile.modelId());
            statement.setString(5, profile.systemPrompt());
            statement.setInt(6, profile.maxTurns());
            statement.setString(7, join(profile.allowedToolNames()));
            statement.setString(8, join(profile.skillIds()));
            statement.setBoolean(9, profile.enabled());
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(String id) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM dsh_sub_agent_profile WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_sub_agent_profile ("
                             + "id VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, mode VARCHAR(32) NOT NULL, "
                             + "model_id VARCHAR(64) NULL, system_prompt TEXT NULL, max_turns INT NOT NULL DEFAULT 8, "
                             + "allowed_tools TEXT NULL, skill_ids TEXT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, "
                             + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                             + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), "
                             + "INDEX idx_dsh_sub_agent_enabled (enabled)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize sub-agent profile schema", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).toList();
    }
}
