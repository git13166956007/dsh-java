package io.github.git13166956007.dsh.security;

public record PolicyDecision(Effect effect, String reason) {
    public PolicyDecision {
        if (effect == null) throw new IllegalArgumentException("policy effect is required");
    }

    public static PolicyDecision allow() {
        return new PolicyDecision(Effect.ALLOW, null);
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(Effect.DENY, reason);
    }

    public static PolicyDecision requireApproval(String reason) {
        return new PolicyDecision(Effect.REQUIRE_APPROVAL, reason);
    }

    public enum Effect { ALLOW, DENY, REQUIRE_APPROVAL }
}
