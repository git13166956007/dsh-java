package io.github.git13166956007.dsh.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MariaDbMcpServerStoreIntegrationTest {
    @Test
    void persistsEndpointSecretsEncryptedAndRestoresThemAfterRegistryRecreation() throws Exception {
        String url = System.getenv().getOrDefault("DSH_DB_URL", "jdbc:mariadb://127.0.0.1:3307/dsh");
        String user = System.getenv().getOrDefault("DSH_DB_USER", "dsh");
        String password = System.getenv().getOrDefault("DSH_DB_PASSWORD", "dsh-local-password");
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            // Database is available; the assertions below are authoritative.
        } catch (Exception exception) {
            assumeTrue(false, "MariaDB integration test skipped: " + exception.getMessage());
            return;
        }

        String name = "mcp-query-" + UUID.randomUUID();
        String secret = "query-test-value";
        String masterKey = "integration-only-master-key";
        ObjectMapper mapper = new ObjectMapper();
        MariaDbMcpServerStore store = new MariaDbMcpServerStore(url, user, password, mapper, masterKey);
        McpServerRegistry registry = new McpServerRegistry(store);
        try {
            McpServerInfo created = registry.create(name, "streamable_http",
                    "https://example.test/mcp?apiKey=" + secret + "&version=1", null, List.of());

            assertEquals("https://example.test/mcp?version=1", created.endpoint());
            assertEquals(secret, registry.credentials(created.id()).queryParameters().get("apiKey"));

            try (Connection connection = DriverManager.getConnection(url, user, password);
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT endpoint, query_params_json FROM dsh_mcp_server WHERE id=?")) {
                statement.setString(1, created.id());
                try (ResultSet rows = statement.executeQuery()) {
                    assertEquals(true, rows.next());
                    assertEquals(created.endpoint(), rows.getString("endpoint"));
                    String encrypted = rows.getString("query_params_json");
                    assertNotNull(encrypted);
                    assertFalse(encrypted.contains(secret));
                    assertEquals(true, encrypted.contains("enc:v1:"));
                }
            }

            McpServerRegistry restored = new McpServerRegistry(
                    new MariaDbMcpServerStore(url, user, password, mapper, masterKey));
            assertEquals(created.endpoint(), restored.find(created.id()).endpoint());
            assertEquals(secret, restored.credentials(created.id()).queryParameters().get("apiKey"));
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM dsh_mcp_server WHERE name=?")) {
                statement.setString(1, name);
                statement.executeUpdate();
            }
        }
    }
}
