package io.github.git13166956007.dsh.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ModelResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ModelRegistryTest {
    @Test
    void seedsDefaultProfileAndHidesApiKeyFromPublicProfile() {
        InMemoryModelProfileStore store = new InMemoryModelProfileStore();
        ModelRegistry registry = new ModelRegistry(store, "https://api.deepseek.com", "deepseek",
                "deepseek-v4-flash", "secret-key", "127.0.0.1", 7897);

        ModelProfile profile = registry.list().get(0);
        assertEquals("deepseek-v4-flash", profile.model());
        assertTrue(profile.active());
        assertTrue(profile.apiKeyConfigured());
        assertFalse(registry.find("default").toString().contains("secret-key"));
    }

    @Test
    void activatingOneProfileDeactivatesThePreviousDefault() {
        ModelRegistry registry = new ModelRegistry(new InMemoryModelProfileStore(),
                "https://api.deepseek.com", "deepseek", "deepseek-v4-flash", "", "", 0);
        ModelProfile second = registry.create("Local", "openai_compatible", "http://localhost:9999/v1",
                "local-model", "", "", 0, true, false);

        registry.activate(second.id());

        assertTrue(registry.find(second.id()).active());
        assertFalse(registry.find("default").active());
    }

    @Test
    void persistsModelCapabilities() {
        InMemoryModelProfileStore store = new InMemoryModelProfileStore();
        ModelRegistry registry = new ModelRegistry(store, "https://api.deepseek.com", "deepseek",
                "deepseek-v4-flash", "", "", 0);
        ModelProfile created = registry.create("Vision local", "openai_compatible", "http://localhost:9999/v1",
                "vision-model", "", "", 0, true, false, false, false, true, 131072);

        ModelRegistry restored = new ModelRegistry(store, "https://api.deepseek.com", "deepseek",
                "deepseek-v4-flash", "", "", 0);
        ModelProfile profile = restored.find(created.id());
        assertFalse(profile.supportsTools());
        assertFalse(profile.supportsStreaming());
        assertTrue(profile.supportsVision());
        assertEquals(131072, profile.contextWindow());
    }

    @Test
    void encryptsAndDecryptsSecretsWithoutExposingPlaintext() {
        SecretCipher cipher = new SecretCipher("local-master-key");
        String encrypted = cipher.encrypt("api-key-value");

        assertTrue(encrypted.startsWith("enc:v1:"));
        assertTrue(!encrypted.contains("api-key-value"));
        assertEquals("api-key-value", cipher.decrypt(encrypted));
        assertEquals("legacy-plain", cipher.decrypt("legacy-plain"));
    }

    @Test
    void patchDistinguishesOmittedFieldsFromExplicitNulls() throws Exception {
        InMemoryModelProfileStore store = new InMemoryModelProfileStore();
        ModelRegistry registry = new ModelRegistry(store, "https://api.deepseek.com", "deepseek",
                "deepseek-v4-flash", "stored-key", "", 0);
        ModelProfile created = registry.create("Configured", "deepseek", "https://api.deepseek.com",
                "deepseek-v4-flash", "another-key", "", 0, true, false, true, true, false,
                131072, 0.4, 0.9, 2048, 0.1, 0.2, 60,
                "{\"reasoning_effort\":\"high\"}");

        ObjectMapper mapper = new ObjectMapper();
        registry.update(created.id(), mapper.readTree("{\"enabled\":false}"));
        ModelProfile retained = registry.find(created.id());
        assertEquals(0.4, retained.temperature());
        assertEquals("{\"reasoning_effort\":\"high\"}", retained.requestOptionsJson());
        assertTrue(retained.apiKeyConfigured());

        registry.update(created.id(), mapper.readTree("{\"temperature\":null,\"requestOptionsJson\":null}"));
        ModelProfile cleared = registry.find(created.id());
        assertEquals(null, cleared.temperature());
        assertEquals(null, cleared.requestOptionsJson());
    }

    @Test
    void persistsFallbackModelAndRejectsCyclesAndReferencedDeletes() throws Exception {
        InMemoryModelProfileStore store = new InMemoryModelProfileStore();
        ModelRegistry registry = new ModelRegistry(store, "https://api.deepseek.com", "deepseek",
                "deepseek-v4-flash", "", "", 0);
        ModelProfile backup = registry.create("Backup", "deepseek", "https://api.deepseek.com",
                "deepseek-v4-flash", "", "", 0, true, false);
        ObjectMapper mapper = new ObjectMapper();
        registry.update("default", mapper.readTree("{\"fallbackModelId\":\"" + backup.id() + "\"}"));

        ModelRegistry restored = new ModelRegistry(store, "https://api.deepseek.com", "deepseek",
                "deepseek-v4-flash", "", "", 0);
        assertEquals(backup.id(), restored.find("default").fallbackModelId());
        assertThrows(IllegalArgumentException.class,
                () -> restored.update(backup.id(), mapper.readTree("{\"fallbackModelId\":\"default\"}")));
        assertThrows(IllegalArgumentException.class, () -> restored.delete(backup.id()));
    }

    @Test
    void rejectsSelfFallback() {
        ModelRegistry registry = new ModelRegistry(new InMemoryModelProfileStore(),
                "https://api.deepseek.com", "deepseek", "deepseek-v4-flash", "", "", 0);
        ObjectMapper mapper = new ObjectMapper();

        assertThrows(IllegalArgumentException.class,
                () -> registry.update("default", mapper.readTree("{\"fallbackModelId\":\"default\"}")));
    }

    @Test
    void persistsFailoverPolicy() throws Exception {
        InMemoryModelProfileStore store = new InMemoryModelProfileStore();
        ModelRegistry registry = new ModelRegistry(store, "https://api.deepseek.com", "deepseek",
                "deepseek-v4-flash", "", "", 0);
        registry.update("default", new ObjectMapper().readTree("{\"failoverPolicy\":\"transient_failure\"}"));

        ModelProfile restored = new ModelRegistry(store, "https://api.deepseek.com", "deepseek",
                "deepseek-v4-flash", "", "", 0).find("default");

        assertEquals("transient_failure", restored.failoverPolicy());
    }

    @Test
    void persistsModelTokenPricesAndAllowsClearingThem() throws Exception {
        InMemoryModelProfileStore store = new InMemoryModelProfileStore();
        ModelRegistry registry = new ModelRegistry(store, "https://api.deepseek.com", "deepseek",
                "deepseek-v4-flash", "", "", 0);
        registry.update("default", new ObjectMapper().readTree(
                "{\"inputPricePerMillionTokens\":0.27,\"outputPricePerMillionTokens\":1.10}"));

        ModelProfile restored = new ModelRegistry(store, "https://api.deepseek.com", "deepseek",
                "deepseek-v4-flash", "", "", 0).find("default");

        assertEquals(0.27, restored.inputPricePerMillionTokens());
        assertEquals(1.10, restored.outputPricePerMillionTokens());

        registry.update("default", new ObjectMapper().readTree(
                "{\"inputPricePerMillionTokens\":null,\"outputPricePerMillionTokens\":null}"));
        assertEquals(null, registry.find("default").inputPricePerMillionTokens());
        assertEquals(null, registry.find("default").outputPricePerMillionTokens());
    }

    @Test
    void rejectsNegativeModelTokenPrices() throws Exception {
        ModelRegistry registry = new ModelRegistry(new InMemoryModelProfileStore(),
                "https://api.deepseek.com", "deepseek", "deepseek-v4-flash", "", "", 0);

        assertThrows(IllegalArgumentException.class,
                () -> registry.update("default", new ObjectMapper().readTree(
                        "{\"inputPricePerMillionTokens\":-0.01}")));
    }

    @Test
    void recordsAndPersistsModelUsageAndEstimatedCost() throws Exception {
        InMemoryModelProfileStore profiles = new InMemoryModelProfileStore();
        InMemoryModelHealthStore health = new InMemoryModelHealthStore();
        InMemoryModelUsageStore usages = new InMemoryModelUsageStore();
        ModelRegistry registry = new ModelRegistry(profiles, health, usages,
                "https://api.deepseek.com", "deepseek", "deepseek-v4-flash", "", "", 0);
        registry.update("default", new ObjectMapper().readTree(
                "{\"inputPricePerMillionTokens\":0.27,\"outputPricePerMillionTokens\":1.10}"));

        registry.recordUsage("default", List.of(ChatMessage.user("hello")),
                new ModelResponse("world", List.of(), "stop", 10, 20, 30));

        ModelUsage usage = registry.usage("default");
        assertEquals(1, usage.requestCount());
        assertEquals(10, usage.promptTokens());
        assertEquals(20, usage.completionTokens());
        assertEquals(30, usage.totalTokens());
        assertEquals(0.0000247, usage.estimatedCostUsd(), 0.0000000001);

        ModelRegistry restored = new ModelRegistry(profiles, health, usages,
                "https://api.deepseek.com", "deepseek", "deepseek-v4-flash", "", "", 0);
        assertEquals(30, restored.usage("default").totalTokens());
    }

    @Test
    void estimatesMissingModelUsageWithTheConfiguredTokenizer() throws Exception {
        InMemoryModelUsageStore usages = new InMemoryModelUsageStore();
        ModelRegistry registry = new ModelRegistry(new InMemoryModelProfileStore(),
                new InMemoryModelHealthStore(), usages, "https://api.deepseek.com", "deepseek",
                "deepseek-v4-flash", "", "", 0);

        registry.recordUsage("default", List.of(ChatMessage.user("12345678")),
                new ModelResponse("1234", List.of(), "stop"));

        ModelUsage usage = registry.usage("default");
        assertEquals(2, usage.promptTokens());
        assertEquals(1, usage.completionTokens());
        assertEquals(3, usage.totalTokens());
    }

    @Test
    void normalizesPersistedActiveSelectionAndIgnoresDisabledActiveProfiles() {
        InMemoryModelProfileStore store = new InMemoryModelProfileStore();
        store.save(new ModelProfileData("disabled", "Disabled", "deepseek", "https://api.deepseek.com",
                "disabled-model", "", "", 0, false, true));
        store.save(new ModelProfileData("first", "First", "deepseek", "https://api.deepseek.com",
                "first-model", "", "", 0, true, true));
        store.save(new ModelProfileData("second", "Second", "deepseek", "https://api.deepseek.com",
                "second-model", "", "", 0, true, true));

        ModelRegistry registry = new ModelRegistry(store, "https://api.deepseek.com", "deepseek",
                "deepseek-v4-flash", "", "", 0);

        assertTrue(registry.find("first").active());
        assertFalse(registry.find("disabled").active());
        assertFalse(registry.find("second").active());
        assertEquals("first", registry.resolve(null).id());
    }

    @Test
    void startsWithAllModelsDisabledSoTheyCanBeReenabledFromManagement() {
        InMemoryModelProfileStore store = new InMemoryModelProfileStore();
        store.save(new ModelProfileData("disabled", "Disabled", "deepseek", "https://api.deepseek.com",
                "disabled-model", "", "", 0, false, false));

        ModelRegistry registry = new ModelRegistry(store, "https://api.deepseek.com", "deepseek",
                "deepseek-v4-flash", "", "", 0);

        assertFalse(registry.find("disabled").active());
        assertThrows(IllegalStateException.class, () -> registry.resolve(null));
    }
}
