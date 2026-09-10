package io.github.git13166956007.dsh.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class SubAgentProfileRegistryTest {
    @Test
    void persistsIndependentModeAndCapabilityLists() {
        InMemorySubAgentProfileStore store = new InMemorySubAgentProfileStore();
        SubAgentProfileRegistry registry = new SubAgentProfileRegistry(store, 8);

        SubAgentProfile profile = registry.create("Tool worker", AgentMode.EXECUTION, "local", "Be precise.",
                5, List.of("time_now"), List.of("example"), true);

        assertEquals(AgentMode.EXECUTION, registry.resolve(profile.id()).mode());
        assertEquals(List.of("time_now"), registry.resolve(profile.id()).allowedToolNames());
        assertEquals(List.of("example"), registry.resolve(profile.id()).skillIds());
    }

    @Test
    void persistsExecutionBudgets() {
        InMemorySubAgentProfileStore store = new InMemorySubAgentProfileStore();
        SubAgentProfileRegistry registry = new SubAgentProfileRegistry(store, 8);
        SubAgentProfile created = registry.create("Bounded worker", AgentMode.EXECUTION, null, "", 5,
                List.of(), List.of(), true, 12, 45, 3);

        SubAgentProfileRegistry restored = new SubAgentProfileRegistry(store, 8);
        SubAgentProfile profile = restored.find(created.id());
        assertEquals(12, profile.maxToolCalls());
        assertEquals(45, profile.timeoutSeconds());
        assertEquals(3, profile.maxDepth());
    }

    @Test
    void rejectsInvalidCapabilityNames() {
        SubAgentProfileRegistry registry = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);

        assertThrows(IllegalArgumentException.class, () -> registry.create("Bad", AgentMode.CHAT, null, "", 8,
                List.of("not allowed"), List.of(), true));
    }
}
