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
        assertEquals(64, registry.resolve(null).maxToolCalls());
        assertEquals(300, registry.resolve(null).timeoutSeconds());
        assertEquals(4, registry.resolve(null).maxDepth());
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

    @Test
    void persistsExecutionBudgets() {
        InMemoryAgentProfileStore store = new InMemoryAgentProfileStore();
        AgentProfileRegistry registry = new AgentProfileRegistry(store, 8);
        AgentProfile profile = registry.create("Bounded", AgentMode.EXECUTION, null, "", 6, true, false,
                12, 45, 2);

        AgentProfile restored = new AgentProfileRegistry(store, 8).find(profile.id());
        assertEquals(12, restored.maxToolCalls());
        assertEquals(45, restored.timeoutSeconds());
        assertEquals(2, restored.maxDepth());
    }
}
