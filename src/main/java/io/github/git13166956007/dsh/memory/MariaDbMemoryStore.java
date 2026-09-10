package io.github.git13166956007.dsh.memory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class MariaDbMemoryStore implements MemoryStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MariaDbMemoryStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public List<MemoryRecordData> list(String namespace, String subjectKey, int limit) throws SQLException {
        return query(namespace, subjectKey, null, limit);
    }

    @Override
    public List<MemoryRecordData> search(String namespace, String subjectKey, String query, int limit) throws SQLException {
        return query(namespace, subjectKey, query, limit);
    }

    @Override
    public MemoryRecordData save(MemoryRecordData memory) throws SQLException {
        if (memory.id() > 0) {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE dsh_memory SET namespace=?, subject_key=?, memory_type=?, content=?, metadata_json=?, importance=? WHERE id=?")) {
                bind(statement, memory);
                statement.setLong(7, memory.id());
                statement.executeUpdate();
                return readById(memory.id());
            }
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_memory (namespace, subject_key, memory_type, content, metadata_json, importance) "
                             + "VALUES (?, ?, ?, ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, memory);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return readById(keys.getLong(1));
            }
        }
    }

    @Override
    public void delete(long id) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM dsh_memory WHERE id=?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    private List<MemoryRecordData> query(String namespace, String subjectKey, String query, int limit) throws SQLException {
        String sql = query == null || query.isBlank()
                ? "SELECT id, namespace, subject_key, memory_type, content, metadata_json, importance, created_at, updated_at "
                + "FROM dsh_memory WHERE namespace=? AND subject_key=? ORDER BY updated_at DESC LIMIT ?"
                : "SELECT id, namespace, subject_key, memory_type, content, metadata_json, importance, created_at, updated_at "
                + "FROM dsh_memory WHERE namespace=? AND subject_key=? AND content LIKE ? ORDER BY importance DESC, updated_at DESC LIMIT ?";
        List<MemoryRecordData> result = new ArrayList<MemoryRecordData>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, subjectKey);
            if (query == null || query.isBlank()) statement.setInt(3, Math.max(1, Math.min(limit, 100)));
            else {
                statement.setString(3, "%" + query.trim() + "%");
                statement.setInt(4, Math.max(1, Math.min(limit, 100)));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(read(rows));
            }
        }
        return result;
    }

    private MemoryRecordData readById(long id) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id, namespace, subject_key, memory_type, content, metadata_json, importance, created_at, updated_at FROM dsh_memory WHERE id=?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("memory was not saved: " + id);
                return read(rows);
            }
        }
    }

    private static void bind(PreparedStatement statement, MemoryRecordData memory) throws SQLException {
        statement.setString(1, memory.namespace());
        statement.setString(2, memory.subjectKey());
        statement.setString(3, memory.memoryType());
        statement.setString(4, memory.content());
        statement.setString(5, memory.metadataJson());
        statement.setDouble(6, memory.importance());
    }

    private static MemoryRecordData read(ResultSet rows) throws SQLException {
        return new MemoryRecordData(rows.getLong("id"), rows.getString("namespace"), rows.getString("subject_key"),
                rows.getString("memory_type"), rows.getString("content"), rows.getString("metadata_json"),
                rows.getDouble("importance"), instant(rows.getTimestamp("created_at")), instant(rows.getTimestamp("updated_at")));
    }

    private void ensureSchema() {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS dsh_memory (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + "namespace VARCHAR(128) NOT NULL, subject_key VARCHAR(255) NOT NULL, memory_type VARCHAR(32) NOT NULL, "
                        + "content LONGTEXT NOT NULL, metadata_json TEXT NULL, importance DECIMAL(5,4) NOT NULL DEFAULT 0.5000, "
                        + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), "
                        + "INDEX idx_dsh_memory_subject (namespace, subject_key, updated_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize memory schema", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }
}
