package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ToolCall;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.git13166956007.dsh.session.event.MariaDbSessionEventLog;
import io.github.git13166956007.dsh.session.event.SessionEventCodec;
import io.github.git13166956007.dsh.session.event.SessionEventLog;
import io.github.git13166956007.dsh.session.event.SessionEventProjection;
import io.github.git13166956007.dsh.session.event.SessionEventTypes;

public final class MariaDbConversationStore implements ConversationStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper;
    private final MariaDbSessionEventLog eventLog;

    public MariaDbConversationStore(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, new ObjectMapper());
    }

    public MariaDbConversationStore(String jdbcUrl, String username, String password, ObjectMapper objectMapper) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.eventLog = new MariaDbSessionEventLog(jdbcUrl, username, password, this.objectMapper);
        ensureSchema();
    }

    @Override
    public String open(String conversationId, String title) throws Exception {
        String id = conversationId == null || conversationId.trim().isEmpty()
                ? UUID.randomUUID().toString() : conversationId.trim();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT IGNORE INTO dsh_conversation (id, title) VALUES (?, ?)")) {
                statement.setString(1, id);
                statement.setString(2, shorten(title));
                int inserted = statement.executeUpdate();
                if (inserted > 0) eventLog.append(connection, id, SessionEventTypes.CREATED,
                        objectMapper.createObjectNode().put("title", shorten(title)));
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
        return id;
    }

    @Override
    public SessionEventLog eventLog() { return eventLog; }

    @Override
    public List<ConversationInfo> list(int limit) throws SQLException {
        if (limit <= 0) return List.of();
        List<ConversationInfo> result = new ArrayList<ConversationInfo>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT c.id, c.title, c.created_at, c.updated_at, c.summary_text, c.summary_message_count, "
                             + "(SELECT COUNT(*) FROM dsh_message m WHERE m.conversation_id=c.id) AS message_count "
                             + "FROM dsh_conversation c")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString("id");
                    SessionEventProjection.Snapshot snapshot = projection(id);
                    if (snapshot.updatedAt() != null && !snapshot.deleted()) {
                        result.add(new ConversationInfo(id, snapshot.title(), snapshot.messages().size(),
                                snapshot.createdAt(), snapshot.updatedAt()));
                    } else if (snapshot.updatedAt() == null) {
                        result.add(readLegacyInfo(rows));
                    }
                }
            }
        }
        return result.stream().sorted(Comparator.comparing(ConversationInfo::updatedAt).reversed()
                        .thenComparing(ConversationInfo::id)).limit(limit).toList();
    }

    @Override
    public void rename(String conversationId, String title) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE dsh_conversation SET title=? WHERE id=?")) {
                statement.setString(1, shorten(title));
                statement.setString(2, conversationId);
                if (statement.executeUpdate() == 0) throw new IllegalArgumentException("unknown conversation: " + conversationId);
                eventLog.append(connection, conversationId, SessionEventTypes.RENAMED,
                        objectMapper.createObjectNode().put("title", shorten(title)));
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override
    public boolean delete(String conversationId) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement messages = connection.prepareStatement(
                    "DELETE FROM dsh_message WHERE conversation_id=?");
                 PreparedStatement conversation = connection.prepareStatement(
                         "DELETE FROM dsh_conversation WHERE id=?")) {
                messages.setString(1, conversationId);
                messages.executeUpdate();
                conversation.setString(1, conversationId);
                boolean deleted = conversation.executeUpdate() > 0;
                if (deleted) eventLog.append(connection, conversationId, SessionEventTypes.DELETED,
                        objectMapper.createObjectNode());
                connection.commit();
                return deleted;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override
    public List<ConversationSearchResult> search(String query, int limit) throws SQLException {
        if (limit <= 0) return List.of();
        List<ConversationSearchResult> result = new ArrayList<ConversationSearchResult>();
        String normalized = query.toLowerCase(java.util.Locale.ROOT);
        try (Connection connection = connection()) {
            for (ConversationInfo info : list(Integer.MAX_VALUE)) {
                if (result.size() >= limit) break;
                SessionEventProjection.Snapshot snapshot = projection(info.id());
                List<ChatMessage> messages = snapshot.updatedAt() == null
                        ? readLegacyMessages(connection, info.id(), Integer.MAX_VALUE)
                        : snapshot.messages();
                String title = snapshot.updatedAt() == null ? info.title() : snapshot.title();
                for (int index = 0; index < messages.size() && result.size() < limit; index++) {
                    ChatMessage message = messages.get(index);
                    if (message.content() != null && message.content().toLowerCase(java.util.Locale.ROOT).contains(normalized)) {
                        result.add(new ConversationSearchResult(info.id(), index, message.role().value(),
                                message.content(), title));
                    }
                }
            }
        }
        return result;
    }

    @Override
    public List<ChatMessage> load(String conversationId, int limit) throws SQLException {
        if (limit <= 0) return List.of();
        try {
            List<io.github.git13166956007.dsh.session.event.SessionEvent> events = eventLog.read(conversationId);
            List<ChatMessage> projected = SessionEventProjection.messages(events);
            if (!events.isEmpty()) {
                int from = Math.max(0, projected.size() - limit);
                return List.copyOf(projected.subList(from, projected.size()));
            }
        } catch (Exception exception) {
            throw new SQLException("failed to read session event stream", exception);
        }
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT role, content, reasoning_content, tool_calls_json, tool_call_id FROM dsh_message "
                             + "WHERE conversation_id = ? ORDER BY turn_no DESC, id DESC LIMIT ?")) {
            statement.setString(1, conversationId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ChatMessage message = readMessage(
                            result.getString("role"), result.getString("content"),
                            result.getString("reasoning_content"),
                            result.getString("tool_calls_json"), result.getString("tool_call_id"));
                    if (message != null) messages.add(0, message);
                }
            }
        }
        return messages;
    }

    @Override
    public void append(String conversationId, ChatMessage message) throws Exception {
        if (message.role() == ChatMessage.Role.SYSTEM) return;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                String eventType = switch (message.role()) {
                    case USER -> SessionEventTypes.USER_MESSAGE;
                    case ASSISTANT -> SessionEventTypes.ASSISTANT_MESSAGE;
                    case TOOL -> SessionEventTypes.TOOL_MESSAGE;
                    case SYSTEM -> null;
                };
                if (eventType != null) eventLog.append(connection, conversationId, eventType,
                        SessionEventCodec.message(objectMapper, message));
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override
    public boolean exists(String conversationId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM dsh_conversation WHERE id=?")) {
            statement.setString(1, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    @Override
    public ConversationSummary loadSummary(String conversationId) throws SQLException {
        SessionEventProjection.Snapshot snapshot = projection(conversationId);
        if (snapshot.updatedAt() != null) return snapshot.summary();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT summary_text, summary_message_count FROM dsh_conversation WHERE id=?")) {
            statement.setString(1, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getString("summary_text") == null) return null;
                return new ConversationSummary(result.getString("summary_text"), result.getInt("summary_message_count"));
            }
        }
    }

    @Override
    public void saveSummary(String conversationId, ConversationSummary summary) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                eventLog.append(connection, conversationId, SessionEventTypes.SUMMARY_UPDATED,
                        objectMapper.createObjectNode().put("content", summary.content())
                                .put("coveredMessageCount", summary.coveredMessageCount()));
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static ConversationInfo readInfo(ResultSet rows) throws SQLException {
        Timestamp created = rows.getTimestamp("created_at");
        Timestamp updated = rows.getTimestamp("updated_at");
        return new ConversationInfo(rows.getString("id"), rows.getString("title"), rows.getInt("message_count"),
                created.toInstant(), updated.toInstant());
    }

    private static ConversationInfo readLegacyInfo(ResultSet rows) throws SQLException {
        Timestamp created = rows.getTimestamp("created_at");
        Timestamp updated = rows.getTimestamp("updated_at");
        return new ConversationInfo(rows.getString("id"), rows.getString("title"), rows.getInt("message_count"),
                created.toInstant(), updated.toInstant());
    }

    private SessionEventProjection.Snapshot projection(String conversationId) throws SQLException {
        try {
            return SessionEventProjection.project(eventLog.read(conversationId));
        } catch (Exception exception) {
            throw sqlException("failed to project session events", exception);
        }
    }

    private List<ChatMessage> readLegacyMessages(Connection connection, String conversationId, int limit)
            throws SQLException {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role, content, reasoning_content, tool_calls_json, tool_call_id FROM dsh_message "
                        + "WHERE conversation_id = ? ORDER BY turn_no, id")) {
            statement.setString(1, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next() && messages.size() < limit) {
                    ChatMessage message = readMessage(result.getString("role"), result.getString("content"),
                            result.getString("reasoning_content"), result.getString("tool_calls_json"),
                            result.getString("tool_call_id"));
                    if (message != null) messages.add(message);
                }
            }
        }
        return messages;
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_conversation ("
                             + "id CHAR(36) NOT NULL PRIMARY KEY, title VARCHAR(255) NOT NULL, "
                             + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                             + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) "
                             + "ON UPDATE CURRENT_TIMESTAMP(3)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_conversation ADD COLUMN IF NOT EXISTS summary_text LONGTEXT NULL")) {
                alter.executeUpdate();
            }
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_conversation ADD COLUMN IF NOT EXISTS summary_message_count INT NOT NULL DEFAULT 0")) {
                alter.executeUpdate();
            }
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_message ADD COLUMN IF NOT EXISTS reasoning_content LONGTEXT NULL")) {
                alter.executeUpdate();
            }
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_message ADD COLUMN IF NOT EXISTS tool_calls_json LONGTEXT NULL")) {
                alter.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize conversation summary schema", exception);
        }
    }

    private ChatMessage readMessage(String role, String content, String reasoningContent, String toolCallsJson,
                                    String toolCallId) throws SQLException {
        if ("user".equals(role)) return ChatMessage.user(content == null ? "" : content);
        if ("assistant".equals(role)) return ChatMessage.assistant(content, readToolCalls(toolCallsJson), reasoningContent);
        if ("tool".equals(role)) return ChatMessage.tool(toolCallId, content == null ? "" : content);
        return null;
    }

    private List<ToolCall> readToolCalls(String value) throws SQLException {
        if (value == null || value.isBlank()) return List.of();
        try {
            JsonNode array = objectMapper.readTree(value);
            if (array == null || !array.isArray()) return List.of();
            List<ToolCall> calls = new ArrayList<ToolCall>();
            for (JsonNode node : array) {
                calls.add(new ToolCall(node.path("id").asString(null), node.path("name").asString(null),
                        node.path("arguments").deepCopy()));
            }
            return calls;
        } catch (Exception exception) {
            throw sqlException("failed to deserialize tool calls", exception);
        }
    }

    private static SQLException sqlException(String message, Exception cause) {
        SQLException exception = new SQLException(message);
        exception.initCause(cause);
        return exception;
    }

    private static String shorten(String title) {
        if (title == null || title.trim().isEmpty()) return "New conversation";
        String value = title.trim();
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
