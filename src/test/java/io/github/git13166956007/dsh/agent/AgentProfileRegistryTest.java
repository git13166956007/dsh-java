package io.github.git13166956007.dsh.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentProfileRegistryTest {
    @Test
    void seedsAnActiveProfileAndPersistsModeSettings() {
        InMemoryAgentProfileStore store = new InMemoryAgentProfileStore();
        AgentProfileRegistry registry = new AgentProfileRegistry(store, 8);

        AgentProfile planning = registry.create("Planner", AgentMode.PLANNING, "default",
                "Prefer small verifiable steps.", 4, true, false);

        registry.activate(planning.id());

        assertEquals(AgentMode.PLANNING, registry.resolve(null).mode());
        assertEquals(4, registry.resolve(null).maxTurns());
        assertFalse(registry.find("default").active());
        assertTrue(registry.find(planning.id()).active());
    }

    @Test
    void disablingActiveProfileSelectsAnotherEnabledProfile() {
        AgentProfileRegistry registry = new AgentProfileRegistry(new InMemoryAgentProfileStore(), 8);
        AgentProfile execution = registry.create("Executor", AgentMode.EXECUTION, null, "", 8, true, false);

        registry.update("default", null, null, null, null, null, false, null);

        assertEquals(execution.id(), registry.resolve(null).id());
        assertTrue(registry.resolve(null).enabled());
    }

    @Test
    void disabledProfileCannotBecomeActiveByDefault() {
        AgentProfileRegistry registry = new AgentProfileRegistry(new InMemoryAgentProfileStore(), 8);
        AgentProfile disabled = registry.create("Disabled", AgentMode.CHAT, null, "", 8, false, false);

        assertFalse(disabled.active());
        assertEquals("default", registry.resolve(null).id());
    }
}
