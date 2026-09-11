package io.github.git13166956007.dsh.session.event;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class MariaDbSessionEventLog implements SessionEventLog {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper;

    public MariaDbSessionEventLog(String jdbcUrl, String username, String password, ObjectMapper objectMapper) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        ensureSchema();
    }

    @Override
    public synchronized SessionEvent append(String sessionId, String type, JsonNode payload) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    SessionEvent event = append(connection, sessionId, type, payload);
                    connection.commit();
                    return event;
                } catch (Exception exception) {
                    connection.rollback();
                    if (attempt >= 4 || !isTransientLockFailure(exception)) throw exception;
                    try {
                        Thread.sleep(20L * (attempt + 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                }
            }
        }
    }

    /** Appends to an existing transaction; the caller owns commit/rollback. */
    public synchronized SessionEvent append(Connection connection, String sessionId, String type,
                                            JsonNode payload) throws Exception {
        long sequence;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT IGNORE INTO dsh_session_event_head (session_id, sequence_no) VALUES (?, 0)")) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sequence_no FROM dsh_session_event_head WHERE session_id=? FOR UPDATE")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("session event head was not created");
                sequence = rows.getLong(1) + 1;
            }
        }
        if (sequence == 1) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(MAX(sequence_no), 0) FROM dsh_session_event WHERE session_id=?")) {
                statement.setString(1, sessionId);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    sequence = rows.getLong(1) + 1;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE dsh_session_event_head SET sequence_no=? WHERE session_id=?")) {
            statement.setLong(1, sequence);
            statement.setString(2, sessionId);
            statement.executeUpdate();
        }
        String id = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO dsh_session_event (id, session_id, sequence_no, occurred_at, event_type, payload_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, sessionId);
            statement.setLong(3, sequence);
            statement.setTimestamp(4, Timestamp.from(occurredAt));
            statement.setString(5, type);
            statement.setString(6, objectMapper.writeValueAsString(payload));
            statement.executeUpdate();
        }
        return new SessionEvent(id, sessionId, sequence, occurredAt, type, payload.deepCopy());
    }

    @Override
    public synchronized List<SessionEvent> read(String sessionId) throws Exception {
        List<SessionEvent> result = new ArrayList<SessionEvent>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, sequence_no, occurred_at, event_type, payload_json FROM dsh_session_event "
                             + "WHERE session_id=? ORDER BY sequence_no")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new SessionEvent(rows.getString("id"), sessionId, rows.getLong("sequence_no"),
                            rows.getTimestamp("occurred_at").toInstant(), rows.getString("event_type"),
                            objectMapper.readTree(rows.getString("payload_json"))));
                }
            }
        }
        return List.copyOf(result);
    }

    private String readId(Connection connection, String sessionId, long sequence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM dsh_session_event WHERE session_id=? AND sequence_no=?")) {
            statement.setString(1, sessionId);
            statement.setLong(2, sequence);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("session event was not persisted");
                return rows.getString(1);
            }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_session_event ("
                             + "id CHAR(36) NOT NULL PRIMARY KEY, session_id CHAR(36) NOT NULL, "
                             + "sequence_no BIGINT NOT NULL, occurred_at TIMESTAMP(3) NOT NULL, "
                             + "event_type VARCHAR(128) NOT NULL, payload_json LONGTEXT NOT NULL, "
                             + "UNIQUE KEY uq_dsh_session_event_sequence (session_id, sequence_no), "
                             + "INDEX idx_dsh_session_event_session (session_id, sequence_no)) "
                             + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
            try (PreparedStatement head = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS dsh_session_event_head ("
                            + "session_id CHAR(36) NOT NULL PRIMARY KEY, sequence_no BIGINT NOT NULL DEFAULT 0) "
                            + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
                head.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize session event schema", exception);
        }
    }

    private static boolean isTransientLockFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && ("40001".equals(sql.getSQLState()) || sql.getErrorCode() == 1205
                    || sql.getErrorCode() == 1213)) return true;
        }
        return false;
    }
}
