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
}
