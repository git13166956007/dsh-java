package io.github.git13166956007.dsh.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
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
    void persistsRoutingPolicyAndEnforcesConcurrentReservations() {
        InMemorySubAgentProfileStore store = new InMemorySubAgentProfileStore();
        SubAgentProfileRegistry registry = new SubAgentProfileRegistry(store, 8);
        SubAgentProfile created = registry.create("Database worker", AgentMode.EXECUTION, null, "Inspect databases", 5,
                List.of(), List.of(), true, 12, 45, 3, 80, 0.25, 1, List.of("database", "migration"));

        SubAgentProfile restored = new SubAgentProfileRegistry(store, 8).find(created.id());
        assertEquals(80, restored.priority());
        assertEquals(0.25, restored.costWeight());
        assertEquals(1, restored.maxConcurrentRuns());
        assertEquals(List.of("database", "migration"), restored.capabilityTags());

        registry.reserve(created.id(), "run-1");
        assertThrows(IllegalStateException.class, () -> registry.reserve(created.id(), "run-2"));
        registry.release(created.id(), "run-1");
        registry.reserve(created.id(), "run-2");
    }

    @Test
    void rejectsInvalidCapabilityNames() {
        SubAgentProfileRegistry registry = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);

        assertThrows(IllegalArgumentException.class, () -> registry.create("Bad", AgentMode.CHAT, null, "", 8,
                List.of("not allowed"), List.of(), true));
    }

    @Test
    void persistsToolPermissionsAlongsideToolAndSkillAllowLists() {
        InMemorySubAgentProfileStore store = new InMemorySubAgentProfileStore();
        SubAgentProfileRegistry registry = new SubAgentProfileRegistry(store, 8);
        SubAgentProfile created = registry.create("Permission worker", AgentMode.EXECUTION, null, "", 8,
                List.of("restricted_tool"), List.of("safe-skill"), true, 4, 30, 2,
                50, 1.0, 1, List.of("security"), Map.of("tool.restricted_tool", "deny"));

        SubAgentProfile restored = new SubAgentProfileRegistry(store, 8).find(created.id());
        assertEquals(List.of("restricted_tool"), restored.allowedToolNames());
        assertEquals(List.of("safe-skill"), restored.skillIds());
        assertEquals("deny", restored.permissions().get("tool.restricted_tool"));
    }
}
