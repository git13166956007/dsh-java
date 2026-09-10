package io.github.git13166956007.dsh.skill;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MariaDbSkillStateStore implements SkillStateStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MariaDbSkillStateStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public Map<String, Boolean> list() throws SQLException {
        Map<String, Boolean> result = new LinkedHashMap<String, Boolean>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("SELECT skill_id, enabled FROM dsh_skill_state");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.put(rows.getString("skill_id"), rows.getBoolean("enabled"));
        }
        return result;
    }

    @Override
    public void save(String skillId, boolean enabled) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_skill_state (skill_id, enabled) VALUES (?, ?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)")) {
            statement.setString(1, skillId);
            statement.setBoolean(2, enabled);
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(String skillId) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM dsh_skill_state WHERE skill_id = ?")) {
            statement.setString(1, skillId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to delete skill state", exception);
        }
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_skill_state (skill_id VARCHAR(255) NOT NULL PRIMARY KEY, "
                             + "enabled BOOLEAN NOT NULL, updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) "
                             + "ON UPDATE CURRENT_TIMESTAMP(3)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize skill state schema", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
