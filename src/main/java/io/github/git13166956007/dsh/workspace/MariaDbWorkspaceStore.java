package io.github.git13166956007.dsh.workspace;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MariaDbWorkspaceStore implements WorkspaceStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MariaDbWorkspaceStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public List<WorkspaceProfileData> list() throws SQLException {
        List<WorkspaceProfileData> result = new ArrayList<WorkspaceProfileData>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, name, directory, enabled, active, write_enabled, max_read_bytes, max_write_bytes, "
                             + "max_process_timeout_seconds, max_process_output_bytes, allowed_commands "
                             + "FROM dsh_workspace_profile ORDER BY created_at, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(new WorkspaceProfileData(rows.getString("id"), rows.getString("name"),
                    rows.getString("directory"), rows.getBoolean("enabled"), rows.getBoolean("active"),
                    rows.getBoolean("write_enabled"), rows.getLong("max_read_bytes"), rows.getLong("max_write_bytes"),
                    rows.getInt("max_process_timeout_seconds"), rows.getLong("max_process_output_bytes"),
                    parseCommands(rows.getString("allowed_commands"))));
        }
        return result;
    }

    @Override
    public void save(WorkspaceProfileData profile) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_workspace_profile (id, name, directory, enabled, active, write_enabled, "
                             + "max_read_bytes, max_write_bytes, max_process_timeout_seconds, max_process_output_bytes, allowed_commands) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name=VALUES(name), "
                             + "directory=VALUES(directory), enabled=VALUES(enabled), active=VALUES(active), "
                             + "write_enabled=VALUES(write_enabled), max_read_bytes=VALUES(max_read_bytes), "
                             + "max_write_bytes=VALUES(max_write_bytes), max_process_timeout_seconds=VALUES(max_process_timeout_seconds), "
                             + "max_process_output_bytes=VALUES(max_process_output_bytes), allowed_commands=VALUES(allowed_commands)")) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.name());
            statement.setString(3, profile.directory());
            statement.setBoolean(4, profile.enabled());
            statement.setBoolean(5, profile.active());
            statement.setBoolean(6, profile.writeEnabled());
            statement.setLong(7, profile.maxReadBytes());
            statement.setLong(8, profile.maxWriteBytes());
            statement.setInt(9, profile.maxProcessTimeoutSeconds());
            statement.setLong(10, profile.maxProcessOutputBytes());
            statement.setString(11, String.join(",", profile.allowedCommands()));
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(String id) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM dsh_workspace_profile WHERE id=?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_workspace_profile (id VARCHAR(64) NOT NULL PRIMARY KEY, "
                             + "name VARCHAR(128) NOT NULL UNIQUE, directory VARCHAR(1000) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, "
                             + "active BOOLEAN NOT NULL DEFAULT FALSE, write_enabled BOOLEAN NOT NULL DEFAULT FALSE, "
                             + "max_read_bytes BIGINT NOT NULL DEFAULT 1000000, max_write_bytes BIGINT NOT NULL DEFAULT 1000000, "
                             + "max_process_timeout_seconds INT NOT NULL DEFAULT 120, max_process_output_bytes BIGINT NOT NULL DEFAULT 1000000, "
                             + "allowed_commands TEXT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                             + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), "
                             + "INDEX idx_dsh_workspace_active (active, enabled)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize workspace schema", exception);
        }
    }

    private static Set<String> parseCommands(String value) {
        Set<String> result = new LinkedHashSet<String>();
        if (value == null || value.isBlank()) return result;
        for (String command : value.split(",")) if (!command.isBlank()) result.add(command.trim());
        return result;
    }
}
