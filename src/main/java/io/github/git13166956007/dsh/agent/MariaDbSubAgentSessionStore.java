package io.github.git13166956007.dsh.agent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class MariaDbSubAgentSessionStore implements SubAgentSessionStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MariaDbSubAgentSessionStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public List<SubAgentSessionData> list() throws SQLException {
        List<SubAgentSessionData> result = new ArrayList<SubAgentSessionData>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, profile_id, conversation_id, status, created_at, updated_at "
                             + "FROM dsh_sub_agent_session ORDER BY created_at DESC, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(read(rows));
        }
        return result;
    }

    @Override
    public SubAgentSessionData find(String id) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, profile_id, conversation_id, status, created_at, updated_at "
                             + "FROM dsh_sub_agent_session WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        }
    }

    @Override
    public void save(SubAgentSessionData session) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_sub_agent_session "
                             + "(id, profile_id, conversation_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE profile_id=VALUES(profile_id), conversation_id=VALUES(conversation_id), "
                             + "status=VALUES(status), created_at=VALUES(created_at), updated_at=VALUES(updated_at)")) {
            statement.setString(1, session.id());
            statement.setString(2, session.profileId());
            statement.setString(3, session.conversationId());
            statement.setString(4, session.status().value());
            statement.setTimestamp(5, Timestamp.from(session.createdAt()));
            statement.setTimestamp(6, Timestamp.from(session.updatedAt()));
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(String id) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM dsh_sub_agent_session WHERE id=?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_sub_agent_session ("
                             + "id CHAR(36) NOT NULL PRIMARY KEY, profile_id VARCHAR(64) NOT NULL, "
                             + "conversation_id CHAR(36) NOT NULL UNIQUE, status VARCHAR(16) NOT NULL, "
                             + "created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL, "
                             + "INDEX idx_dsh_sub_agent_session_profile (profile_id), "
                             + "INDEX idx_dsh_sub_agent_session_status (status)) "
                             + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize sub-agent session schema", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static SubAgentSessionData read(ResultSet rows) throws SQLException {
        return new SubAgentSessionData(rows.getString("id"), rows.getString("profile_id"),
                rows.getString("conversation_id"), SubAgentSessionStatus.parse(rows.getString("status")),
                instant(rows.getTimestamp("created_at")), instant(rows.getTimestamp("updated_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }
}
