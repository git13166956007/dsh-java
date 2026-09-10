package io.github.git13166956007.dsh.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
}
