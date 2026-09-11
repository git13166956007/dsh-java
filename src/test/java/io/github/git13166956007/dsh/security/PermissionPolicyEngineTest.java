package io.github.git13166956007.dsh.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionPolicyEngineTest {
    private final PermissionPolicyEngine engine = new PermissionPolicyEngine();

    @Test
    void resolvesSpecificRulesBeforeWildcards() {
        PolicyRequest request = new PolicyRequest("run-1", "agent-1", "workspace_read",
                JsonNodeFactory.instance.objectNode(), Map.of("*", "deny", "tool.*", "approval",
                        "tool.workspace_read", "allow"));
        assertEquals(PolicyDecision.Effect.ALLOW, engine.evaluate(request).effect());
    }

    @Test
    void supportsApprovalAsAFirstClassDecision() {
        PolicyRequest request = new PolicyRequest("run-1", "agent-1", "workspace_write", null,
                Map.of("tool.workspace_write", "require_approval"));
        assertEquals(PolicyDecision.Effect.REQUIRE_APPROVAL, engine.evaluate(request).effect());
        assertEquals(PolicyDecision.Effect.ALLOW, engine.evaluate(new PolicyRequest("run-1", "agent-1",
                "workspace_write", null, Map.of("tool.workspace_write", "require_approval"), true)).effect());
    }
}
