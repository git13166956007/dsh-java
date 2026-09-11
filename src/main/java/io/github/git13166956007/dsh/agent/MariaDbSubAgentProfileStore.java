package io.github.git13166956007.dsh.agent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class MariaDbSubAgentProfileStore implements SubAgentProfileStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MariaDbSubAgentProfileStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public List<SubAgentProfileData> list() throws SQLException {
        List<SubAgentProfileData> result = new ArrayList<SubAgentProfileData>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, name, mode, model_id, system_prompt, max_turns, allowed_tools, skill_ids, enabled, "
                             + "max_tool_calls, timeout_seconds, max_depth, priority, cost_weight, max_concurrent_runs, capability_tags, permissions_json "
                             + "FROM dsh_sub_agent_profile ORDER BY created_at, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(new SubAgentProfileData(rows.getString("id"), rows.getString("name"),
                    AgentMode.parse(rows.getString("mode")), rows.getString("model_id"), rows.getString("system_prompt"),
                    rows.getInt("max_turns"), split(rows.getString("allowed_tools")), split(rows.getString("skill_ids")),
                    rows.getBoolean("enabled"), rows.getInt("max_tool_calls"), rows.getInt("timeout_seconds"),
                    rows.getInt("max_depth"), rows.getInt("priority"), rows.getDouble("cost_weight"),
                    rows.getInt("max_concurrent_runs"), split(rows.getString("capability_tags")),
                    readMap(rows.getString("permissions_json"))));
        }
        return result;
    }

    @Override
    public void save(SubAgentProfileData profile) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_sub_agent_profile "
                             + "(id, name, mode, model_id, system_prompt, max_turns, allowed_tools, skill_ids, enabled, "
                             + "max_tool_calls, timeout_seconds, max_depth, priority, cost_weight, max_concurrent_runs, capability_tags, permissions_json) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name=VALUES(name), "
                             + "mode=VALUES(mode), model_id=VALUES(model_id), system_prompt=VALUES(system_prompt), "
                             + "max_turns=VALUES(max_turns), allowed_tools=VALUES(allowed_tools), skill_ids=VALUES(skill_ids), "
                             + "enabled=VALUES(enabled), max_tool_calls=VALUES(max_tool_calls), "
                             + "timeout_seconds=VALUES(timeout_seconds), max_depth=VALUES(max_depth), priority=VALUES(priority), "
                             + "cost_weight=VALUES(cost_weight), max_concurrent_runs=VALUES(max_concurrent_runs), "
                             + "capability_tags=VALUES(capability_tags), permissions_json=VALUES(permissions_json)")) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.name());
            statement.setString(3, profile.mode().value());
            statement.setString(4, profile.modelId());
            statement.setString(5, profile.systemPrompt());
            statement.setInt(6, profile.maxTurns());
            statement.setString(7, join(profile.allowedToolNames()));
            statement.setString(8, join(profile.skillIds()));
            statement.setBoolean(9, profile.enabled());
            statement.setInt(10, profile.maxToolCalls());
            statement.setInt(11, profile.timeoutSeconds());
            statement.setInt(12, profile.maxDepth());
            statement.setInt(13, profile.priority());
            statement.setDouble(14, profile.costWeight());
            statement.setInt(15, profile.maxConcurrentRuns());
            statement.setString(16, join(profile.capabilityTags()));
            statement.setString(17, write(profile.permissions()));
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(String id) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM dsh_sub_agent_profile WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_sub_agent_profile ("
                             + "id VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, mode VARCHAR(32) NOT NULL, "
                             + "model_id VARCHAR(64) NULL, system_prompt TEXT NULL, max_turns INT NOT NULL DEFAULT 8, "
                             + "allowed_tools TEXT NULL, skill_ids TEXT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, "
                             + "max_tool_calls INT NOT NULL DEFAULT 64, timeout_seconds INT NOT NULL DEFAULT 300, "
                             + "max_depth INT NOT NULL DEFAULT 4, priority INT NOT NULL DEFAULT 50, "
                             + "cost_weight DOUBLE NOT NULL DEFAULT 1.0, max_concurrent_runs INT NOT NULL DEFAULT 4, "
                             + "capability_tags TEXT NULL, "
                             + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                             + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), "
                             + "INDEX idx_dsh_sub_agent_enabled (enabled)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_sub_agent_profile ADD COLUMN IF NOT EXISTS max_tool_calls INT NOT NULL DEFAULT 64")) {
                alter.executeUpdate();
            }
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_sub_agent_profile ADD COLUMN IF NOT EXISTS timeout_seconds INT NOT NULL DEFAULT 300")) {
                alter.executeUpdate();
            }
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_sub_agent_profile ADD COLUMN IF NOT EXISTS max_depth INT NOT NULL DEFAULT 4")) {
                alter.executeUpdate();
            }
            addColumn(connection, "priority INT NOT NULL DEFAULT 50");
            addColumn(connection, "cost_weight DOUBLE NOT NULL DEFAULT 1.0");
            addColumn(connection, "max_concurrent_runs INT NOT NULL DEFAULT 4");
            addColumn(connection, "capability_tags TEXT NULL");
            addColumn(connection, "permissions_json TEXT NULL");
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize sub-agent profile schema", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    private static void addColumn(Connection connection, String definition) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "ALTER TABLE dsh_sub_agent_profile ADD COLUMN IF NOT EXISTS " + definition)) {
            statement.executeUpdate();
        }
    }

    private String write(Map<String, String> value) throws SQLException {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw sqlException("failed to serialize sub-agent permissions", exception); }
    }

    private Map<String, String> readMap(String value) throws SQLException {
        if (value == null || value.isBlank()) return Map.of();
        try {
            JsonNode node = objectMapper.readTree(value);
            Map<String, String> result = new java.util.LinkedHashMap<String, String>();
            if (node != null && node.isObject()) node.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asString("")));
            return Map.copyOf(result);
        } catch (Exception exception) { throw sqlException("failed to deserialize sub-agent permissions", exception); }
    }

    private static SQLException sqlException(String message, Exception cause) {
        SQLException exception = new SQLException(message);
        exception.initCause(cause);
        return exception;
    }
}
