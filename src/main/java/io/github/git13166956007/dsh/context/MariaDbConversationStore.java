package io.github.git13166956007.dsh.context;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MariaDbConversationStore implements ConversationStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MariaDbConversationStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
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
    public List<ChatMessage> load(String conversationId, int limit) throws SQLException {
        if (limit <= 0) return List.of();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT role, content, tool_call_id FROM dsh_message "
                             + "WHERE conversation_id = ? ORDER BY turn_no DESC, id DESC LIMIT ?")) {
            statement.setString(1, conversationId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ChatMessage message = readMessage(
                            result.getString("role"), result.getString("content"),
                            result.getString("tool_call_id"));
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
                                + "(conversation_id, turn_no, role, content, tool_call_id) "
                                + "VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, conversationId);
                    statement.setInt(2, turnNo);
                    statement.setString(3, message.role().value());
                    statement.setString(4, message.content());
                    statement.setString(5, message.toolCallId());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static ChatMessage readMessage(String role, String content, String toolCallId) {
        if ("user".equals(role)) return ChatMessage.user(content == null ? "" : content);
        if ("assistant".equals(role)) return ChatMessage.assistant(content, List.of());
        if ("tool".equals(role)) return ChatMessage.tool(toolCallId, content == null ? "" : content);
        return null;
    }

    private static String shorten(String title) {
        if (title == null || title.trim().isEmpty()) return "New conversation";
        String value = title.trim();
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
