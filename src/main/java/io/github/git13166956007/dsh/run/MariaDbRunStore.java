package io.github.git13166956007.dsh.run;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class MariaDbRunStore implements RunStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MariaDbRunStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public List<RunData> listRuns() throws SQLException {
        List<RunData> result = new ArrayList<RunData>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, parent_run_id, kind, status, conversation_id, plan_id, step_id, agent_id, model_id, "
                             + "started_at, completed_at, error_text, output_text FROM dsh_run ORDER BY started_at DESC, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(readRun(rows));
        }
        return result;
    }

    @Override
    public List<RunEventData> listEvents(String runId) throws SQLException {
        List<RunEventData> result = new ArrayList<RunEventData>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT event_id, run_id, event_key, event_type, payload, created_at FROM dsh_run_event WHERE run_id=? ORDER BY event_id")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new RunEventData(rows.getLong("event_id"), rows.getString("run_id"),
                        rows.getString("event_key"), rows.getString("event_type"), rows.getString("payload"),
                        instant(rows.getTimestamp("created_at"))));
            }
        }
        return result;
    }

    @Override
    public void saveRun(RunData run) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_run (id, parent_run_id, kind, status, conversation_id, plan_id, step_id, agent_id, model_id, "
                             + "started_at, completed_at, error_text, output_text) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE parent_run_id=VALUES(parent_run_id), kind=VALUES(kind), status=VALUES(status), "
                             + "conversation_id=VALUES(conversation_id), plan_id=VALUES(plan_id), step_id=VALUES(step_id), "
                             + "agent_id=VALUES(agent_id), model_id=VALUES(model_id), started_at=VALUES(started_at), "
                             + "completed_at=VALUES(completed_at), error_text=VALUES(error_text), output_text=VALUES(output_text)")) {
            statement.setString(1, run.id());
            statement.setString(2, run.parentRunId());
            statement.setString(3, run.kind().value());
            statement.setString(4, run.status().value());
            statement.setString(5, run.conversationId());
            statement.setString(6, run.planId());
            statement.setString(7, run.stepId());
            statement.setString(8, run.agentId());
            statement.setString(9, run.modelId());
            statement.setTimestamp(10, Timestamp.from(run.startedAt()));
            setTimestamp(statement, 11, run.completedAt());
            statement.setString(12, run.error());
            statement.setString(13, run.output());
            statement.executeUpdate();
        }
    }

    @Override
    public boolean compareAndSetStatus(RunData expected, RunData next) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE dsh_run SET parent_run_id=?, kind=?, status=?, conversation_id=?, plan_id=?, step_id=?, "
                             + "agent_id=?, model_id=?, started_at=?, completed_at=?, error_text=?, output_text=? "
                             + "WHERE id=? AND status=?")) {
            statement.setString(1, next.parentRunId());
            statement.setString(2, next.kind().value());
            statement.setString(3, next.status().value());
            statement.setString(4, next.conversationId());
            statement.setString(5, next.planId());
            statement.setString(6, next.stepId());
            statement.setString(7, next.agentId());
            statement.setString(8, next.modelId());
            statement.setTimestamp(9, Timestamp.from(next.startedAt()));
            setTimestamp(statement, 10, next.completedAt());
            statement.setString(11, next.error());
            statement.setString(12, next.output());
            statement.setString(13, expected.id());
            statement.setString(14, expected.status().value());
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public RunEventData compareAndSetStatusAndEvent(RunData expected, RunData next, RunEventData event)
            throws SQLException {
        RunEventSaveResult result = compareAndSetStatusAndEventResult(expected, next, event);
        return result == null ? null : result.event();
    }

    @Override
    public RunEventSaveResult compareAndSetStatusAndEventResult(RunData expected, RunData next, RunEventData event)
            throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE dsh_run SET parent_run_id=?, kind=?, status=?, conversation_id=?, plan_id=?, step_id=?, "
                                + "agent_id=?, model_id=?, started_at=?, completed_at=?, error_text=?, output_text=? "
                                + "WHERE id=? AND status=?")) {
                    statement.setString(1, next.parentRunId());
                    statement.setString(2, next.kind().value());
                    statement.setString(3, next.status().value());
                    statement.setString(4, next.conversationId());
                    statement.setString(5, next.planId());
                    statement.setString(6, next.stepId());
                    statement.setString(7, next.agentId());
                    statement.setString(8, next.modelId());
                    statement.setTimestamp(9, Timestamp.from(next.startedAt()));
                    setTimestamp(statement, 10, next.completedAt());
                    statement.setString(11, next.error());
                    statement.setString(12, next.output());
                    statement.setString(13, expected.id());
                    statement.setString(14, expected.status().value());
                    updated = statement.executeUpdate();
                }
                if (updated != 1) {
                    connection.rollback();
                    return null;
                }
                RunEventSaveResult saved = saveEventResult(connection, event);
                connection.commit();
                return saved;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override
    public RunEventData saveEvent(RunEventData event) throws SQLException {
        return saveEventResult(event).event();
    }

    @Override
    public RunEventSaveResult saveEventResult(RunEventData event) throws SQLException {
        try (Connection connection = connection()) {
            return saveEventResult(connection, event);
        }
    }

    private RunEventSaveResult saveEventResult(Connection connection, RunEventData event) throws SQLException {
        if (event.eventKey() != null && !event.eventKey().isBlank()) {
            RunEventData existing = findEventByKey(connection, event.runId(), event.eventKey());
            if (existing != null) return new RunEventSaveResult(verifyIdempotent(existing, event), false);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_run_event (run_id, event_key, event_type, payload, created_at) VALUES (?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, event.runId());
            statement.setString(2, event.eventKey());
            statement.setString(3, event.type());
            statement.setString(4, event.payload());
            statement.setTimestamp(5, Timestamp.from(event.createdAt()));
            try {
                statement.executeUpdate();
            } catch (SQLException exception) {
                if (event.eventKey() == null || event.eventKey().isBlank() || !isDuplicateKey(exception)) throw exception;
                RunEventData existing = findEventByKey(connection, event.runId(), event.eventKey());
                if (existing == null) throw exception;
                return new RunEventSaveResult(verifyIdempotent(existing, event), false);
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return new RunEventSaveResult(new RunEventData(keys.getLong(1), event.runId(), event.eventKey(),
                            event.type(), event.payload(), event.createdAt()), true);
                }
            }
        }
        return new RunEventSaveResult(event, true);
    }

    private RunEventData findEventByKey(Connection connection, String runId, String eventKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                     "SELECT event_id, run_id, event_key, event_type, payload, created_at FROM dsh_run_event "
                             + "WHERE run_id=? AND event_key=?")) {
            statement.setString(1, runId);
            statement.setString(2, eventKey);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new RunEventData(rows.getLong("event_id"), rows.getString("run_id"),
                        rows.getString("event_key"), rows.getString("event_type"), rows.getString("payload"),
                        instant(rows.getTimestamp("created_at")));
            }
        }
    }

    private static RunEventData verifyIdempotent(RunEventData existing, RunEventData requested) {
        if (!java.util.Objects.equals(existing.type(), requested.type())
                || !java.util.Objects.equals(existing.payload(), requested.payload())) {
            throw new IllegalArgumentException("run event key was already used with different content: "
                    + requested.eventKey());
        }
        return existing;
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_run (id CHAR(36) NOT NULL PRIMARY KEY, parent_run_id CHAR(36) NULL, "
                             + "kind VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL, conversation_id CHAR(36) NULL, "
                             + "plan_id VARCHAR(64) NULL, step_id VARCHAR(64) NULL, agent_id VARCHAR(64) NULL, model_id VARCHAR(64) NULL, "
                             + "started_at TIMESTAMP(3) NOT NULL, completed_at TIMESTAMP(3) NULL, error_text LONGTEXT NULL, "
                             + "output_text LONGTEXT NULL, INDEX idx_dsh_run_parent (parent_run_id), INDEX idx_dsh_run_plan (plan_id), "
                             + "INDEX idx_dsh_run_conversation (conversation_id), INDEX idx_dsh_run_started (started_at)) "
                             + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize run schema", exception);
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_run_event (event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                             + "run_id CHAR(36) NOT NULL, event_key VARCHAR(191) NULL, event_type VARCHAR(64) NOT NULL, payload LONGTEXT NULL, "
                             + "created_at TIMESTAMP(3) NOT NULL, CONSTRAINT fk_dsh_run_event_run FOREIGN KEY (run_id) "
                             + "REFERENCES dsh_run (id) ON DELETE CASCADE, UNIQUE KEY uq_dsh_run_event_key (run_id, event_key), "
                             + "INDEX idx_dsh_run_event_run (run_id, event_id)) "
                             + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
            addEventKeyColumn(connection);
            addEventKeyIndex(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize run event schema", exception);
        }
    }

    private static void addEventKeyColumn(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "ALTER TABLE dsh_run_event ADD COLUMN event_key VARCHAR(191) NULL")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (!isDuplicateColumn(exception)) throw exception;
        }
    }

    private static void addEventKeyIndex(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "ALTER TABLE dsh_run_event ADD UNIQUE KEY uq_dsh_run_event_key (run_id, event_key)")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (!isDuplicateKey(exception)) throw exception;
        }
    }

    private static boolean isDuplicateColumn(SQLException exception) {
        String message = exception.getMessage();
        return exception.getErrorCode() == 1060 || (message != null && message.toLowerCase().contains("duplicate column"));
    }

    private static boolean isDuplicateKey(SQLException exception) {
        String message = exception.getMessage();
        return exception.getErrorCode() == 1061 || exception.getErrorCode() == 1062
                || (message != null && message.toLowerCase().contains("duplicate"));
    }

    private RunData readRun(ResultSet rows) throws SQLException {
        return new RunData(rows.getString("id"), rows.getString("parent_run_id"), RunKind.parse(rows.getString("kind")),
                RunStatus.parse(rows.getString("status")), rows.getString("conversation_id"), rows.getString("plan_id"),
                rows.getString("step_id"), rows.getString("agent_id"), rows.getString("model_id"),
                instant(rows.getTimestamp("started_at")), nullableInstant(rows.getTimestamp("completed_at")),
                rows.getString("error_text"), rows.getString("output_text"));
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }

    private static Instant nullableInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void setTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) statement.setTimestamp(index, null);
        else statement.setTimestamp(index, Timestamp.from(value));
    }
}
