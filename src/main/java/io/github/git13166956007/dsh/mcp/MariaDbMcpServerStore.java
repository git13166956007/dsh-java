package io.github.git13166956007.dsh.mcp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

public final class MariaDbMcpServerStore implements McpServerStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper;

    public MariaDbMcpServerStore(String jdbcUrl, String username, String password, ObjectMapper objectMapper) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.objectMapper = objectMapper;
        ensureSchema();
    }

    @Override
    public List<McpServerInfo> list() throws SQLException {
        List<McpServerInfo> result = new ArrayList<McpServerInfo>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, name, transport, endpoint, command, arguments_json, enabled FROM dsh_mcp_server ORDER BY created_at, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new McpServerInfo(rows.getString("id"), rows.getString("name"), rows.getString("transport"),
                        rows.getString("endpoint"), rows.getString("command"), readArguments(rows.getString("arguments_json")),
                        rows.getBoolean("enabled"), "DISCONNECTED"));
            }
        }
        return result;
    }

    @Override
    public void save(McpServerInfo server) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_mcp_server (id, name, transport, endpoint, command, arguments_json, enabled) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name=VALUES(name), transport=VALUES(transport), "
                             + "endpoint=VALUES(endpoint), command=VALUES(command), arguments_json=VALUES(arguments_json), enabled=VALUES(enabled)")) {
            statement.setString(1, server.id());
            statement.setString(2, server.name());
            statement.setString(3, server.transport());
            statement.setString(4, server.endpoint());
            statement.setString(5, server.command());
            statement.setString(6, objectMapper.writeValueAsString(server.arguments()));
            statement.setBoolean(7, server.enabled());
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(String id) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM dsh_mcp_server WHERE id=?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_mcp_server (id VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL UNIQUE, "
                             + "transport VARCHAR(16) NOT NULL, endpoint VARCHAR(1000) NULL, command VARCHAR(1000) NULL, "
                             + "arguments_json TEXT NULL, environment_json TEXT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, "
                             + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at TIMESTAMP(3) NOT NULL "
                             + "DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_mcp_server MODIFY COLUMN id VARCHAR(64) NOT NULL")) {
                alter.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize MCP server schema", exception);
        }
    }

    private List<String> readArguments(String json) throws SQLException {
        if (json == null || json.isBlank()) return List.of();
        try {
            java.util.List<String> result = new java.util.ArrayList<String>();
            for (tools.jackson.databind.JsonNode node : objectMapper.readTree(json).asArray()) {
                result.add(node.asString());
            }
            return result;
        } catch (Exception exception) {
            throw new SQLException("invalid MCP arguments JSON", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
