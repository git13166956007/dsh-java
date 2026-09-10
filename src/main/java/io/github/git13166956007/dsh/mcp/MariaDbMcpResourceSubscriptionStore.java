package io.github.git13166956007.dsh.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class MariaDbMcpResourceSubscriptionStore implements McpResourceSubscriptionStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MariaDbMcpResourceSubscriptionStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public List<McpResourceSubscription> list(String serverId) throws SQLException {
        List<McpResourceSubscription> result = new ArrayList<McpResourceSubscription>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT server_id, uri, subscribed_at FROM dsh_mcp_resource_subscription "
                             + "WHERE server_id=? ORDER BY subscribed_at, uri")) {
            statement.setString(1, serverId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Timestamp subscribedAt = rows.getTimestamp("subscribed_at");
                    result.add(new McpResourceSubscription(rows.getString("server_id"), rows.getString("uri"),
                            subscribedAt == null ? null : subscribedAt.toInstant()));
                }
            }
        }
        return result;
    }

    @Override
    public void save(McpResourceSubscription subscription) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_mcp_resource_subscription (server_id, uri, uri_hash, subscribed_at) "
                             + "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE uri=VALUES(uri), subscribed_at=VALUES(subscribed_at)")) {
            statement.setString(1, subscription.serverId());
            statement.setString(2, subscription.uri());
            statement.setBytes(3, hash(subscription.uri()));
            statement.setTimestamp(4, Timestamp.from(subscription.subscribedAt() == null
                    ? Instant.now() : subscription.subscribedAt()));
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(String serverId, String uri) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM dsh_mcp_resource_subscription WHERE server_id=? AND uri_hash=?")) {
            statement.setString(1, serverId);
            statement.setBytes(2, hash(uri));
            statement.executeUpdate();
        }
    }

    @Override
    public void deleteServer(String serverId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM dsh_mcp_resource_subscription WHERE server_id=?")) {
            statement.setString(1, serverId);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_mcp_resource_subscription ("
                             + "server_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL, "
                             + "uri TEXT NOT NULL, uri_hash BINARY(32) NOT NULL, "
                             + "subscribed_at TIMESTAMP(3) NOT NULL, PRIMARY KEY (server_id, uri_hash), "
                             + "CONSTRAINT fk_dsh_mcp_subscription_server FOREIGN KEY (server_id) "
                             + "REFERENCES dsh_mcp_server(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize MCP resource subscription schema", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static byte[] hash(String uri) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(uri.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
