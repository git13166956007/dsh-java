package io.github.git13166956007.dsh.security;

/** Compatibility policy for the current profile permission map. */
public final class PermissionPolicyEngine implements PolicyEngine {
    @Override
    public PolicyDecision evaluate(PolicyRequest request) {
        String decision = request.permissions().get("tool." + request.toolName());
        if (decision == null) decision = request.permissions().get(request.toolName());
        if (decision == null) decision = request.permissions().get("tool.*");
        if (decision == null) decision = request.permissions().get("*");
        if (decision == null || decision.isBlank()
                || "allow".equalsIgnoreCase(decision) || "true".equalsIgnoreCase(decision)
                || "enabled".equalsIgnoreCase(decision)) {
            return PolicyDecision.allow();
        }
        if ("approval".equalsIgnoreCase(decision) || "require_approval".equalsIgnoreCase(decision)
                || "prompt".equalsIgnoreCase(decision)) {
            if (request.approvalGranted()) return PolicyDecision.allow();
            return PolicyDecision.requireApproval("policy requires approval: " + request.toolName());
        }
        if ("deny".equalsIgnoreCase(decision) || "false".equalsIgnoreCase(decision)
                || "disabled".equalsIgnoreCase(decision)) {
            return PolicyDecision.deny("tool permission denied: " + request.toolName());
        }
        return PolicyDecision.allow();
    }
}
