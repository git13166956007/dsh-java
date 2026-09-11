package io.github.git13166956007.dsh.event;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class MariaDbEventJournal implements EventJournal {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper;

    public MariaDbEventJournal(String jdbcUrl, String username, String password, ObjectMapper objectMapper) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        ensureSchema();
    }

    @Override
    public synchronized void append(EventRecord record) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_event_journal (event_name, payload_json, value_json, accepted, reason, error, occurred_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, record.eventName());
            statement.setString(2, objectMapper.writeValueAsString(record.payload()));
            statement.setString(3, record.value() == null ? null : objectMapper.writeValueAsString(record.value()));
            statement.setBoolean(4, record.accepted());
            statement.setString(5, record.reason());
            statement.setString(6, record.error());
            statement.setTimestamp(7, Timestamp.from(record.occurredAt()));
            statement.executeUpdate();
        }
    }

    @Override
    public synchronized List<EventRecord> read() throws Exception {
        List<EventRecord> result = new ArrayList<EventRecord>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT event_name, payload_json, value_json, accepted, reason, error, occurred_at "
                             + "FROM dsh_event_journal ORDER BY id")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    JsonNode payload = objectMapper.readTree(rows.getString("payload_json"));
                    String valueJson = rows.getString("value_json");
                    JsonNode value = valueJson == null ? null : objectMapper.readTree(valueJson);
                    result.add(new EventRecord(rows.getString("event_name"), payload, value,
                            rows.getBoolean("accepted"), rows.getString("reason"), rows.getString("error"),
                            rows.getTimestamp("occurred_at").toInstant()));
                }
            }
        }
        return List.copyOf(result);
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_event_journal ("
                             + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                             + "event_name VARCHAR(255) NOT NULL, payload_json LONGTEXT NOT NULL, "
                             + "value_json LONGTEXT NULL, accepted BOOLEAN NOT NULL, reason TEXT NULL, "
                             + "error TEXT NULL, occurred_at TIMESTAMP(3) NOT NULL, "
                             + "INDEX idx_dsh_event_journal_time (occurred_at, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize event journal schema", exception);
        }
    }
}
