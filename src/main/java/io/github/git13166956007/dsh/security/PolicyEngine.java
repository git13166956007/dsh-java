package io.github.git13166956007.dsh.security;

@FunctionalInterface
public interface PolicyEngine {
    PolicyDecision evaluate(PolicyRequest request);
}
