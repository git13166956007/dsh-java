package io.github.git13166956007.dsh.model;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class MariaDbModelProfileStore implements ModelProfileStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final SecretCipher secrets;

    public MariaDbModelProfileStore(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, null);
    }

    public MariaDbModelProfileStore(String jdbcUrl, String username, String password, String masterKey) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.secrets = new SecretCipher(masterKey);
        ensureSchema();
    }

    @Override
    public List<ModelProfileData> list() throws SQLException {
        List<ModelProfileData> result = new ArrayList<ModelProfileData>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, name, provider, base_url, model_name, api_key, proxy_host, proxy_port, enabled, active, "
                             + "fallback_model_id, supports_tools, supports_streaming, supports_vision, context_window, temperature, top_p, "
                             + "max_tokens, frequency_penalty, presence_penalty, timeout_seconds, request_options_json "
                             + ", failover_policy, input_price_per_million_tokens, output_price_per_million_tokens "
                             + "FROM dsh_model_profile ORDER BY created_at, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new ModelProfileData(rows.getString("id"), rows.getString("name"),
                        rows.getString("provider"), rows.getString("base_url"), rows.getString("model_name"),
                        secrets.decrypt(rows.getString("api_key")), rows.getString("proxy_host"), rows.getInt("proxy_port"),
                        rows.getBoolean("enabled"), rows.getBoolean("active"), rows.getBoolean("supports_tools"),
                        rows.getBoolean("supports_streaming"), rows.getBoolean("supports_vision"), rows.getInt("context_window"),
                        getDouble(rows, "temperature"), getDouble(rows, "top_p"), getInteger(rows, "max_tokens"),
                        getDouble(rows, "frequency_penalty"), getDouble(rows, "presence_penalty"), rows.getInt("timeout_seconds"),
                        rows.getString("request_options_json"), rows.getString("fallback_model_id"),
                        rows.getString("failover_policy"), getDouble(rows, "input_price_per_million_tokens"),
                        getDouble(rows, "output_price_per_million_tokens")));
            }
        }
        return result;
    }

    @Override
    public void save(ModelProfileData profile) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_model_profile "
                             + "(id, name, provider, base_url, model_name, api_key, proxy_host, proxy_port, enabled, active, "
                             + "fallback_model_id, supports_tools, supports_streaming, supports_vision, context_window, temperature, top_p, "
                             + "max_tokens, frequency_penalty, presence_penalty, timeout_seconds, request_options_json, "
                             + "failover_policy, input_price_per_million_tokens, output_price_per_million_tokens) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE name=VALUES(name), provider=VALUES(provider), "
                             + "base_url=VALUES(base_url), model_name=VALUES(model_name), api_key=VALUES(api_key), "
                             + "proxy_host=VALUES(proxy_host), proxy_port=VALUES(proxy_port), "
                             + "enabled=VALUES(enabled), active=VALUES(active), fallback_model_id=VALUES(fallback_model_id), supports_tools=VALUES(supports_tools), "
                             + "supports_streaming=VALUES(supports_streaming), supports_vision=VALUES(supports_vision), "
                             + "context_window=VALUES(context_window), temperature=VALUES(temperature), top_p=VALUES(top_p), "
                             + "max_tokens=VALUES(max_tokens), frequency_penalty=VALUES(frequency_penalty), "
                             + "presence_penalty=VALUES(presence_penalty), timeout_seconds=VALUES(timeout_seconds), "
                             + "request_options_json=VALUES(request_options_json), failover_policy=VALUES(failover_policy), "
                             + "input_price_per_million_tokens=VALUES(input_price_per_million_tokens), "
                             + "output_price_per_million_tokens=VALUES(output_price_per_million_tokens)")) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.name());
            statement.setString(3, profile.provider());
            statement.setString(4, profile.baseUrl());
            statement.setString(5, profile.model());
            statement.setString(6, secrets.encrypt(profile.apiKey()));
            statement.setString(7, profile.proxyHost());
            statement.setInt(8, profile.proxyPort());
            statement.setBoolean(9, profile.enabled());
            statement.setBoolean(10, profile.active());
            statement.setString(11, profile.fallbackModelId());
            statement.setBoolean(12, profile.supportsTools());
            statement.setBoolean(13, profile.supportsStreaming());
            statement.setBoolean(14, profile.supportsVision());
            statement.setInt(15, profile.contextWindow());
            if (profile.temperature() == null) statement.setNull(16, java.sql.Types.DOUBLE);
            else statement.setDouble(16, profile.temperature());
            if (profile.topP() == null) statement.setNull(17, java.sql.Types.DOUBLE);
            else statement.setDouble(17, profile.topP());
            if (profile.maxTokens() == null) statement.setNull(18, java.sql.Types.INTEGER);
            else statement.setInt(18, profile.maxTokens());
            if (profile.frequencyPenalty() == null) statement.setNull(19, java.sql.Types.DOUBLE);
            else statement.setDouble(19, profile.frequencyPenalty());
            if (profile.presencePenalty() == null) statement.setNull(20, java.sql.Types.DOUBLE);
            else statement.setDouble(20, profile.presencePenalty());
            statement.setInt(21, profile.timeoutSeconds());
            statement.setString(22, profile.requestOptionsJson());
            statement.setString(23, profile.failoverPolicy());
            if (profile.inputPricePerMillionTokens() == null) statement.setNull(24, java.sql.Types.DOUBLE);
            else statement.setDouble(24, profile.inputPricePerMillionTokens());
            if (profile.outputPricePerMillionTokens() == null) statement.setNull(25, java.sql.Types.DOUBLE);
            else statement.setDouble(25, profile.outputPricePerMillionTokens());
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(String id) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM dsh_model_profile WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS dsh_model_profile ("
                             + "id VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, "
                             + "provider VARCHAR(64) NOT NULL, base_url VARCHAR(1000) NOT NULL, "
                             + "model_name VARCHAR(255) NOT NULL, api_key LONGTEXT NULL, "
                             + "proxy_host VARCHAR(255) NULL, proxy_port INT NOT NULL DEFAULT 0, "
                             + "enabled BOOLEAN NOT NULL DEFAULT TRUE, active BOOLEAN NOT NULL DEFAULT FALSE, "
                             + "fallback_model_id VARCHAR(64) NULL, "
                             + "supports_tools BOOLEAN NOT NULL DEFAULT TRUE, supports_streaming BOOLEAN NOT NULL DEFAULT TRUE, "
                             + "supports_vision BOOLEAN NOT NULL DEFAULT FALSE, context_window INT NOT NULL DEFAULT 0, "
                             + "temperature DOUBLE NULL, top_p DOUBLE NULL, max_tokens INT NULL, "
                             + "frequency_penalty DOUBLE NULL, presence_penalty DOUBLE NULL, timeout_seconds INT NOT NULL DEFAULT 120, "
                             + "request_options_json LONGTEXT NULL, failover_policy VARCHAR(32) NOT NULL DEFAULT 'any_failure', "
                             + "input_price_per_million_tokens DOUBLE NULL, output_price_per_million_tokens DOUBLE NULL, "
                             + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                             + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), "
                             + "INDEX idx_dsh_model_active (active, enabled)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_model_profile ADD COLUMN IF NOT EXISTS supports_tools BOOLEAN NOT NULL DEFAULT TRUE")) {
                alter.executeUpdate();
            }
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_model_profile ADD COLUMN IF NOT EXISTS supports_streaming BOOLEAN NOT NULL DEFAULT TRUE")) {
                alter.executeUpdate();
            }
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_model_profile ADD COLUMN IF NOT EXISTS supports_vision BOOLEAN NOT NULL DEFAULT FALSE")) {
                alter.executeUpdate();
            }
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE dsh_model_profile ADD COLUMN IF NOT EXISTS context_window INT NOT NULL DEFAULT 0")) {
                alter.executeUpdate();
            }
            addColumn(connection, "temperature DOUBLE NULL");
            addColumn(connection, "top_p DOUBLE NULL");
            addColumn(connection, "max_tokens INT NULL");
            addColumn(connection, "frequency_penalty DOUBLE NULL");
            addColumn(connection, "presence_penalty DOUBLE NULL");
            addColumn(connection, "timeout_seconds INT NOT NULL DEFAULT 120");
            addColumn(connection, "request_options_json LONGTEXT NULL");
            addColumn(connection, "fallback_model_id VARCHAR(64) NULL");
            addColumn(connection, "failover_policy VARCHAR(32) NOT NULL DEFAULT 'any_failure'");
            addColumn(connection, "input_price_per_million_tokens DOUBLE NULL");
            addColumn(connection, "output_price_per_million_tokens DOUBLE NULL");
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize model profile schema", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static Double getDouble(ResultSet rows, String column) throws SQLException {
        double value = rows.getDouble(column);
        return rows.wasNull() ? null : value;
    }

    private static Integer getInteger(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private static void addColumn(Connection connection, String definition) throws SQLException {
        String column = definition.substring(0, definition.indexOf(' '));
        try (PreparedStatement alter = connection.prepareStatement(
                "ALTER TABLE dsh_model_profile ADD COLUMN IF NOT EXISTS " + column + " "
                        + definition.substring(column.length() + 1))) {
            alter.executeUpdate();
        }
    }
}
