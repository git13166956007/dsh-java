package io.github.git13166956007.dsh.tool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public final class MariaDbToolProfileStore implements ToolProfileStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper;

    public MariaDbToolProfileStore(String jdbcUrl, String username, String password, ObjectMapper objectMapper) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.objectMapper = objectMapper;
        ensureSchema();
    }

    @Override
    public List<ToolProfileData> list() throws SQLException {
        List<ToolProfileData> result = new ArrayList<ToolProfileData>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT name, description, input_schema_json, result_text, enabled, approval_required FROM dsh_tool_definition "
                             + "WHERE source_type='custom' ORDER BY name");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new ToolProfileData(rows.getString("name"), rows.getString("description"),
                        objectMapper.readTree(rows.getString("input_schema_json")).isObject()
                                ? (ObjectNode) objectMapper.readTree(rows.getString("input_schema_json"))
                                : objectMapper.createObjectNode(),
                        rows.getString("result_text"), rows.getBoolean("enabled"), rows.getBoolean("approval_required")));
            }
        }
        return result;
    }

    @Override
    public void save(ToolProfileData profile) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_tool_definition (name, source_type, description, input_schema_json, result_text, enabled, approval_required) "
                             + "VALUES (?, 'custom', ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE source_type='custom', "
                             + "description=VALUES(description), input_schema_json=VALUES(input_schema_json), "
                             + "result_text=VALUES(result_text), enabled=VALUES(enabled), approval_required=VALUES(approval_required)")) {
            statement.setString(1, profile.name());
            statement.setString(2, profile.description());
            statement.setString(3, objectMapper.writeValueAsString(profile.parameters()));
            statement.setString(4, profile.result());
            statement.setBoolean(5, profile.enabled());
            statement.setBoolean(6, profile.approvalRequired());
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(String name) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM dsh_tool_definition WHERE name=? AND source_type='custom'")) {
            statement.setString(1, name);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_tool_definition (name VARCHAR(128) NOT NULL PRIMARY KEY, "
                             + "source_type VARCHAR(32) NOT NULL, source_id VARCHAR(255) NULL, description TEXT NOT NULL, "
                             + "input_schema_json LONGTEXT NOT NULL, result_text LONGTEXT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, "
                             + "approval_required BOOLEAN NOT NULL DEFAULT FALSE, "
                             + "version BIGINT NOT NULL DEFAULT 1, updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) "
                             + "ON UPDATE CURRENT_TIMESTAMP(3), INDEX idx_dsh_tool_source (source_type, source_id)) "
                             + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_tool_definition ADD COLUMN IF NOT EXISTS result_text LONGTEXT NULL")) {
                alter.executeUpdate();
            }
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_tool_definition ADD COLUMN IF NOT EXISTS approval_required BOOLEAN NOT NULL DEFAULT FALSE")) {
                alter.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize tool profile schema", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
