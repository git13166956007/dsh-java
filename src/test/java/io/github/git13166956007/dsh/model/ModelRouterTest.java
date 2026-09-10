package io.github.git13166956007.dsh.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ModelRouterTest {
    @Test
    void routesOpenAiCompatibleProfilesAndAllowsRequestKeyOverride() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<String>();
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ModelRegistry registry = new ModelRegistry(new InMemoryModelProfileStore(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "deepseek", "fallback",
                    "fallback-key", "", 0);
            ModelProfile profile = registry.create("Compatible", "openai_compatible",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model",
                    "stored-key", "", 0, true, false, true, true, false, 32768,
                    0.4, 0.85, 512, 0.1, 0.2, 7,
                    "{\"reasoning_effort\":\"high\",\"response_format\":{\"type\":\"text\"}}");

            ModelResponse result = new ModelRouter(registry, new ObjectMapper()).complete(
                    List.of(ChatMessage.user("ping")), List.of(), "request-key", profile.id());

            assertEquals("pong", result.content());
            assertEquals("HEALTHY", registry.health(profile.id()).status());
            assertEquals(1, registry.health(profile.id()).successCount());
            org.junit.jupiter.api.Assertions.assertNotNull(registry.health(profile.id()).lastLatencyMs());
            assertEquals("Bearer request-key", authorization.get());
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"model\":\"test-model\""));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"temperature\":0.4"));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"top_p\":0.85"));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"max_tokens\":512"));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"frequency_penalty\":0.1"));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"presence_penalty\":0.2"));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"reasoning_effort\":\"high\""));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"response_format\":{\"type\":\"text\"}"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsA200NonSseErrorInsteadOfTreatingItAsAnEmptySuccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\",\"infocode\":\"10001\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ModelRegistry registry = new ModelRegistry(new InMemoryModelProfileStore(),
                    "https://api.deepseek.com", "deepseek", "fallback", "key", "", 0);
            ModelProfile profile = registry.create("Invalid SSE", "openai_compatible",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model",
                    "key", "", 0, true, false);

            Exception failure = assertThrows(Exception.class, () -> new ModelRouter(registry, new ObjectMapper()).stream(
                    List.of(ChatMessage.user("ping")), List.of(), null, profile.id(), ignored -> { }));

            org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("without an SSE payload"));
            assertEquals("UNHEALTHY", registry.health(profile.id()).status());
            assertEquals(1, registry.health(profile.id()).failureCount());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnsupportedProviderInsteadOfSilentlyUsingDeepSeek() {
        ModelRegistry registry = new ModelRegistry(new InMemoryModelProfileStore(),
                "https://api.deepseek.com", "deepseek", "fallback", "key", "", 0);
        ModelProfile profile = registry.create("Unknown", "unsupported_provider", "https://example.com/v1",
                "test-model", "key", "", 0, true, false);

        assertThrows(IllegalArgumentException.class, () -> new ModelRouter(registry, new ObjectMapper())
                .complete(List.of(ChatMessage.user("ping")), List.of(), null, profile.id()));
    }

    @Test
    void rejectsRequestOptionsThatOverrideChatProtocolFields() {
        ModelRegistry registry = new ModelRegistry(new InMemoryModelProfileStore(),
                "https://api.deepseek.com", "deepseek", "fallback", "key", "", 0);

        assertThrows(IllegalArgumentException.class, () -> registry.create("Invalid", "deepseek",
                "https://api.deepseek.com", "test-model", "key", "", 0, true, false,
                true, true, false, 32768, null, null, null, null, null, 120,
                "{\"messages\":[]}"));
    }

    @Test
    void recordsFailedRequestsWithoutChangingTheOriginalException() {
        ModelRegistry registry = new ModelRegistry(new InMemoryModelProfileStore(), new InMemoryModelHealthStore(),
                "http://127.0.0.1:1/v1", "openai_compatible", "fallback", "key", "", 0);
        ModelProfile profile = registry.create("Unavailable", "openai_compatible", "http://127.0.0.1:1/v1",
                "test-model", "key", "", 0, true, false);

        assertThrows(Exception.class, () -> new ModelRouter(registry, new ObjectMapper())
                .complete(List.of(ChatMessage.user("ping")), List.of(), null, profile.id()));
        assertEquals("UNHEALTHY", registry.health(profile.id()).status());
        assertEquals(1, registry.health(profile.id()).failureCount());
        org.junit.jupiter.api.Assertions.assertNotNull(registry.health(profile.id()).lastError());
    }

    @Test
    void fallsBackToTheNextEnabledModelAndTracksHealthSeparately() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"backup pong\"},\"finish_reason\":\"stop\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            InMemoryModelProfileStore store = new InMemoryModelProfileStore();
            ModelRegistry registry = new ModelRegistry(store, new InMemoryModelHealthStore(),
                    "http://127.0.0.1:1/v1", "openai_compatible", "unavailable", "key", "", 0);
            ModelProfile primary = registry.create("Primary", "openai_compatible", "http://127.0.0.1:1/v1",
                    "primary", "key", "", 0, true, false);
            ModelProfile backup = registry.create("Backup", "openai_compatible",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "backup", "key", "", 0,
                    true, false);
            registry.update(primary.id(), new ObjectMapper().readTree(
                    "{\"fallbackModelId\":\"" + backup.id() + "\"}"));

            ModelResponse response = new ModelRouter(registry, new ObjectMapper()).complete(
                    List.of(ChatMessage.user("ping")), List.of(), null, primary.id());

            assertEquals("backup pong", response.content());
            assertEquals("UNHEALTHY", registry.health(primary.id()).status());
            assertEquals("HEALTHY", registry.health(backup.id()).status());
            assertEquals(1, registry.health(primary.id()).failureCount());
            assertEquals(1, registry.health(backup.id()).successCount());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transientPolicyFallsBackForNetworkFailures() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger backupCalls = new AtomicInteger();
        ModelRegistry registry = new ModelRegistry(new InMemoryModelProfileStore(),
                "https://api.deepseek.com", "deepseek", "fallback", "key", "", 0);
        registry.registerProvider(new ModelProvider() {
            @Override
            public String id() {
                return "failure_fixture";
            }

            @Override
            public ChatModel create(ModelProfileData profile, ObjectMapper objectMapper) {
                return (messages, tools) -> {
                    if (profile.model().equals("primary")) {
                        primaryCalls.incrementAndGet();
                        throw new IOException("temporary network failure");
                    }
                    backupCalls.incrementAndGet();
                    return new ModelResponse("backup", List.of(), "stop");
                };
            }
        });
        ModelProfile primary = registry.create("Primary", "failure_fixture", "https://example.com",
                "primary", "key", "", 0, true, false);
        ModelProfile backup = registry.create("Backup", "failure_fixture", "https://example.com",
                "backup", "key", "", 0, true, false);
        registry.update(primary.id(), new ObjectMapper().readTree(
                "{\"fallbackModelId\":\"" + backup.id() + "\",\"failoverPolicy\":\"transient_failure\"}"));

        assertEquals("backup", new ModelRouter(registry, new ObjectMapper()).complete(
                List.of(ChatMessage.user("ping")), List.of(), null, primary.id()).content());
        assertEquals(1, primaryCalls.get());
        assertEquals(1, backupCalls.get());
    }

    @Test
    void transientPolicyDoesNotHideConfigurationFailures() {
        AtomicInteger backupCalls = new AtomicInteger();
        ModelRegistry registry = new ModelRegistry(new InMemoryModelProfileStore(),
                "https://api.deepseek.com", "deepseek", "fallback", "key", "", 0);
        registry.registerProvider(new ModelProvider() {
            @Override
            public String id() {
                return "config_fixture";
            }

            @Override
            public ChatModel create(ModelProfileData profile, ObjectMapper objectMapper) {
                return (messages, tools) -> {
                    if (profile.model().equals("primary")) throw new IllegalArgumentException("bad config");
                    backupCalls.incrementAndGet();
                    return new ModelResponse("backup", List.of(), "stop");
                };
            }
        });
        ModelProfile primary = registry.create("Primary", "config_fixture", "https://example.com",
                "primary", "key", "", 0, true, false);
        ModelProfile backup = registry.create("Backup", "config_fixture", "https://example.com",
                "backup", "key", "", 0, true, false);
        registry.update(primary.id(), new ObjectMapper().readTree(
                "{\"fallbackModelId\":\"" + backup.id() + "\",\"failoverPolicy\":\"transient_failure\"}"));

        assertThrows(IllegalArgumentException.class, () -> new ModelRouter(registry, new ObjectMapper()).complete(
                List.of(ChatMessage.user("ping")), List.of(), null, primary.id()));
        assertEquals(0, backupCalls.get());
    }
}
