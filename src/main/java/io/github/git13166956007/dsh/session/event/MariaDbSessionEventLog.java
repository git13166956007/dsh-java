package io.github.git13166956007.dsh.session.event;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        return append(sessionId, UUID.randomUUID().toString(), type, payload);
    }

    @Override
    public synchronized SessionEvent append(String sessionId, String eventId, String type,
                                            JsonNode payload) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    SessionEvent event = append(connection, sessionId, eventId, type, payload);
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
        return append(connection, sessionId, UUID.randomUUID().toString(), type, payload);
    }

    /** Appends to an existing transaction with an idempotency key; the caller owns commit/rollback. */
    public synchronized SessionEvent append(Connection connection, String sessionId, String eventId,
                                            String type, JsonNode payload) throws Exception {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("event ID must not be blank");
        SessionEvent existing = readById(connection, eventId);
        if (existing != null) return verifyIdempotent(existing, sessionId, type, payload);

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
        existing = readById(connection, eventId);
        if (existing != null) return verifyIdempotent(existing, sessionId, type, payload);
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
        String id = eventId;
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        try {
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
        } catch (SQLException exception) {
            if (!isDuplicateKey(exception)) throw exception;
            SessionEvent duplicate = readById(connection, eventId);
            if (duplicate == null) throw exception;
            try (PreparedStatement rollbackHead = connection.prepareStatement(
                    "UPDATE dsh_session_event_head SET sequence_no=sequence_no-1 "
                            + "WHERE session_id=? AND sequence_no=?")) {
                rollbackHead.setString(1, sessionId);
                rollbackHead.setLong(2, sequence);
                rollbackHead.executeUpdate();
            }
            return verifyIdempotent(duplicate, sessionId, type, payload);
        }
        return new SessionEvent(id, sessionId, sequence, occurredAt, type, payload.deepCopy());
    }

    @Override
    public synchronized List<SessionEvent> read(String sessionId) throws Exception {
        try (Connection connection = connection()) {
            return read(connection, sessionId);
        }
    }

    @Override
    public synchronized SessionEventPage read(String sessionId, long afterSequence, int limit) throws Exception {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("session ID is required");
        if (afterSequence < 0) throw new IllegalArgumentException("session event sequence must not be negative");
        if (limit <= 0) throw new IllegalArgumentException("session event page size must be positive");
        List<SessionEvent> result = new ArrayList<SessionEvent>();
        long nextSequence = afterSequence;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, sequence_no, occurred_at, event_type, payload_json FROM dsh_session_event "
                             + "WHERE session_id=? AND sequence_no>? ORDER BY sequence_no LIMIT ?")) {
            statement.setString(1, sessionId);
            statement.setLong(2, afterSequence);
            statement.setInt(3, limit + 1);
            try (ResultSet rows = statement.executeQuery()) {
                boolean hasMore = false;
                while (rows.next()) {
                    if (result.size() == limit) {
                        hasMore = true;
                        break;
                    }
                    nextSequence = rows.getLong("sequence_no");
                    result.add(new SessionEvent(rows.getString("id"), sessionId, nextSequence,
                            rows.getTimestamp("occurred_at").toInstant(), rows.getString("event_type"),
                            objectMapper.readTree(rows.getString("payload_json"))));
                }
                return new SessionEventPage(result, nextSequence, hasMore);
            }
        }
    }

    public synchronized List<SessionEvent> read(Connection connection, String sessionId) throws Exception {
        List<SessionEvent> result = new ArrayList<SessionEvent>();
        try (PreparedStatement statement = connection.prepareStatement(
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

    private SessionEvent readById(Connection connection, String eventId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, session_id, sequence_no, occurred_at, event_type, payload_json "
                        + "FROM dsh_session_event WHERE id=?")) {
            statement.setString(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new SessionEvent(rows.getString("id"), rows.getString("session_id"),
                        rows.getLong("sequence_no"), rows.getTimestamp("occurred_at").toInstant(),
                        rows.getString("event_type"), objectMapper.readTree(rows.getString("payload_json")));
            }
        }
    }

    private static SessionEvent verifyIdempotent(SessionEvent existing, String sessionId, String type,
                                                 JsonNode payload) {
        if (!existing.sessionId().equals(sessionId) || !existing.type().equals(type)
                || !existing.payload().equals(payload)) {
            throw new IllegalArgumentException("event ID was already used with different content: " + existing.id());
        }
        return existing;
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

    private static boolean isDuplicateKey(SQLException exception) {
        return exception.getErrorCode() == 1062 || exception.getErrorCode() == 1586;
    }
}
