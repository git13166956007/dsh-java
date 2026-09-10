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

    public MariaDbModelProfileStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public List<ModelProfileData> list() throws SQLException {
        List<ModelProfileData> result = new ArrayList<ModelProfileData>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, name, provider, base_url, model_name, api_key, proxy_host, proxy_port, enabled, active "
                             + "FROM dsh_model_profile ORDER BY created_at, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new ModelProfileData(rows.getString("id"), rows.getString("name"),
                        rows.getString("provider"), rows.getString("base_url"), rows.getString("model_name"),
                        rows.getString("api_key"), rows.getString("proxy_host"), rows.getInt("proxy_port"),
                        rows.getBoolean("enabled"), rows.getBoolean("active")));
            }
        }
        return result;
    }

    @Override
    public void save(ModelProfileData profile) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_model_profile "
                             + "(id, name, provider, base_url, model_name, api_key, proxy_host, proxy_port, enabled, active) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE name=VALUES(name), provider=VALUES(provider), "
                             + "base_url=VALUES(base_url), model_name=VALUES(model_name), api_key=VALUES(api_key), "
                             + "proxy_host=VALUES(proxy_host), proxy_port=VALUES(proxy_port), "
                             + "enabled=VALUES(enabled), active=VALUES(active)")) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.name());
            statement.setString(3, profile.provider());
            statement.setString(4, profile.baseUrl());
            statement.setString(5, profile.model());
            statement.setString(6, profile.apiKey());
            statement.setString(7, profile.proxyHost());
            statement.setInt(8, profile.proxyPort());
            statement.setBoolean(9, profile.enabled());
            statement.setBoolean(10, profile.active());
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
                             + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                             + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), "
                             + "INDEX idx_dsh_model_active (active, enabled)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize model profile schema", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
