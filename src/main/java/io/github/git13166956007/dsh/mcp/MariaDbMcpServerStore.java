package io.github.git13166956007.dsh.mcp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.git13166956007.dsh.model.SecretCipher;
import tools.jackson.databind.ObjectMapper;

public final class MariaDbMcpServerStore implements McpServerStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper;
    private final SecretCipher secrets;

    public MariaDbMcpServerStore(String jdbcUrl, String username, String password, ObjectMapper objectMapper) {
        this(jdbcUrl, username, password, objectMapper, null);
    }

    public MariaDbMcpServerStore(String jdbcUrl, String username, String password, ObjectMapper objectMapper,
                                 String masterKey) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.objectMapper = objectMapper;
        this.secrets = new SecretCipher(masterKey);
        ensureSchema();
    }

    @Override
    public List<McpServerInfo> list() throws SQLException {
        List<McpServerInfo> result = new ArrayList<McpServerInfo>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, name, transport, endpoint, command, arguments_json, credential_ref, enabled "
                             + ", approval_required FROM dsh_mcp_server ORDER BY created_at, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new McpServerInfo(rows.getString("id"), rows.getString("name"), rows.getString("transport"),
                        rows.getString("endpoint"), rows.getString("command"), readArguments(rows.getString("arguments_json")),
                        rows.getBoolean("enabled"), rows.getBoolean("approval_required"), "DISCONNECTED",
                        rows.getString("credential_ref"), List.of(), List.of()));
            }
        }
        return result;
    }

    @Override
    public void save(McpServerInfo server) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_mcp_server (id, name, transport, endpoint, command, arguments_json, credential_ref, enabled, approval_required) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name=VALUES(name), transport=VALUES(transport), "
                             + "endpoint=VALUES(endpoint), command=VALUES(command), arguments_json=VALUES(arguments_json), "
                             + "credential_ref=VALUES(credential_ref), enabled=VALUES(enabled), approval_required=VALUES(approval_required)")) {
            statement.setString(1, server.id());
            statement.setString(2, server.name());
            statement.setString(3, server.transport());
            statement.setString(4, server.endpoint());
            statement.setString(5, server.command());
            statement.setString(6, objectMapper.writeValueAsString(server.arguments()));
            statement.setString(7, server.credentialRef());
            statement.setBoolean(8, server.enabled());
            statement.setBoolean(9, server.approvalRequired());
            statement.executeUpdate();
        }
    }

    @Override
    public McpServerSecrets loadSecrets(String id) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT headers_json, environment_json FROM dsh_mcp_server WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return McpServerSecrets.empty();
                return new McpServerSecrets(readMap(rows.getString("headers_json")),
                        readMap(rows.getString("environment_json")));
            }
        }
    }

    @Override
    public void saveSecrets(String id, McpServerSecrets value) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE dsh_mcp_server SET headers_json=?, environment_json=? WHERE id=?")) {
            statement.setString(1, writeMap(value == null ? Map.of() : value.headers()));
            statement.setString(2, writeMap(value == null ? Map.of() : value.environment()));
            statement.setString(3, id);
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
                             + "arguments_json TEXT NULL, headers_json LONGTEXT NULL, environment_json LONGTEXT NULL, "
                             + "credential_ref VARCHAR(255) NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, "
                             + "approval_required BOOLEAN NOT NULL DEFAULT TRUE, "
                             + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at TIMESTAMP(3) NOT NULL "
                             + "DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_mcp_server MODIFY COLUMN id VARCHAR(64) NOT NULL")) {
                alter.executeUpdate();
            }
            addColumn(connection, "headers_json LONGTEXT NULL");
            addColumn(connection, "credential_ref VARCHAR(255) NULL");
            addColumn(connection, "approval_required BOOLEAN NOT NULL DEFAULT TRUE");
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

    private Map<String, String> readMap(String json) throws SQLException {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> result = new LinkedHashMap<String, String>();
            tools.jackson.databind.JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) return Map.of();
            for (Map.Entry<String, tools.jackson.databind.JsonNode> entry : node.properties()) {
                result.put(entry.getKey(), secrets.decrypt(entry.getValue().asString()));
            }
            return result;
        } catch (Exception exception) {
            throw new SQLException("invalid MCP secret JSON", exception);
        }
    }

    private String writeMap(Map<String, String> values) throws SQLException {
        try {
            tools.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
            if (values != null) values.forEach((key, value) -> node.put(key, secrets.encrypt(value)));
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new SQLException("failed to encode MCP secret JSON", exception);
        }
    }

    private static void addColumn(Connection connection, String definition) throws SQLException {
        String column = definition.substring(0, definition.indexOf(' '));
        try (PreparedStatement alter = connection.prepareStatement(
                "ALTER TABLE dsh_mcp_server ADD COLUMN IF NOT EXISTS " + column + " " + definition.substring(column.length() + 1))) {
            alter.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
