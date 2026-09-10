package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ToolCall;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class MariaDbConversationStore implements ConversationStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper;

    public MariaDbConversationStore(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, new ObjectMapper());
    }

    public MariaDbConversationStore(String jdbcUrl, String username, String password, ObjectMapper objectMapper) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        ensureSchema();
    }

    @Override
    public String open(String conversationId, String title) throws SQLException {
        String id = conversationId == null || conversationId.trim().isEmpty()
                ? UUID.randomUUID().toString() : conversationId.trim();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT IGNORE INTO dsh_conversation (id, title) VALUES (?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, shorten(title));
            statement.executeUpdate();
        }
        return id;
    }

    @Override
    public List<ConversationInfo> list(int limit) throws SQLException {
        if (limit <= 0) return List.of();
        List<ConversationInfo> result = new ArrayList<ConversationInfo>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT c.id, c.title, c.created_at, c.updated_at, COUNT(m.id) AS message_count "
                             + "FROM dsh_conversation c LEFT JOIN dsh_message m ON m.conversation_id = c.id "
                             + "GROUP BY c.id, c.title, c.created_at, c.updated_at "
                             + "ORDER BY c.updated_at DESC, c.id LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(readInfo(rows));
            }
        }
        return result;
    }

    @Override
    public void rename(String conversationId, String title) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE dsh_conversation SET title=? WHERE id=?")) {
            statement.setString(1, shorten(title));
            statement.setString(2, conversationId);
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("unknown conversation: " + conversationId);
        }
    }

    @Override
    public boolean delete(String conversationId) throws SQLException {
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
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT c.id, c.title, m.role, m.content, "
                             + "(SELECT COUNT(*) FROM dsh_message before_message "
                             + "WHERE before_message.conversation_id=m.conversation_id "
                             + "AND (before_message.turn_no < m.turn_no "
                             + "OR (before_message.turn_no=m.turn_no AND before_message.id <= m.id))) - 1 AS message_index "
                             + "FROM dsh_message m JOIN dsh_conversation c ON c.id=m.conversation_id "
                             + "WHERE LOWER(COALESCE(m.content, '')) LIKE ? "
                             + "ORDER BY c.updated_at DESC, m.turn_no, m.id LIMIT ?")) {
            statement.setString(1, "%" + query.toLowerCase(java.util.Locale.ROOT) + "%");
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new ConversationSearchResult(rows.getString("id"),
                        rows.getInt("message_index"), rows.getString("role"), rows.getString("content"),
                        rows.getString("title")));
            }
        }
        return result;
    }

    @Override
    public List<ChatMessage> load(String conversationId, int limit) throws SQLException {
        if (limit <= 0) return List.of();
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
    public void append(String conversationId, ChatMessage message) throws SQLException {
        if (message.role() == ChatMessage.Role.SYSTEM) return;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement nextTurn = connection.prepareStatement(
                    "SELECT COALESCE(MAX(turn_no), 0) + 1 FROM dsh_message WHERE conversation_id = ?")) {
                nextTurn.setString(1, conversationId);
                int turnNo;
                try (ResultSet result = nextTurn.executeQuery()) {
                    result.next();
                    turnNo = result.getInt(1);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO dsh_message "
                                + "(conversation_id, turn_no, role, content, reasoning_content, tool_calls_json, tool_call_id) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, conversationId);
                    statement.setInt(2, turnNo);
                    statement.setString(3, message.role().value());
                    statement.setString(4, message.content());
                    statement.setString(5, message.reasoningContent());
                    statement.setString(6, writeToolCalls(message.toolCalls()));
                    statement.setString(7, message.toolCallId());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
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
    public void saveSummary(String conversationId, ConversationSummary summary) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE dsh_conversation SET summary_text=?, summary_message_count=? WHERE id=?")) {
            statement.setString(1, summary.content());
            statement.setInt(2, summary.coveredMessageCount());
            statement.setString(3, conversationId);
            statement.executeUpdate();
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

    private String writeToolCalls(List<ToolCall> calls) throws SQLException {
        if (calls == null || calls.isEmpty()) return null;
        try {
            ArrayNode array = objectMapper.createArrayNode();
            for (ToolCall call : calls) {
                ObjectNode node = array.addObject();
                if (call.id() == null) node.putNull("id"); else node.put("id", call.id());
                if (call.name() == null) node.putNull("name"); else node.put("name", call.name());
                node.set("arguments", call.arguments() == null ? objectMapper.createObjectNode() : call.arguments().deepCopy());
            }
            return objectMapper.writeValueAsString(array);
        } catch (Exception exception) {
            throw sqlException("failed to serialize tool calls", exception);
        }
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
