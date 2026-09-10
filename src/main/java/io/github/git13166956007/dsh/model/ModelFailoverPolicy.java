package io.github.git13166956007.dsh.model;

import java.util.Locale;

public enum ModelFailoverPolicy {
    ANY_FAILURE("any_failure"),
    TRANSIENT_FAILURE("transient_failure"),
    DISABLED("disabled");

    private final String value;

    ModelFailoverPolicy(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ModelFailoverPolicy parse(String value) {
        if (value == null || value.isBlank()) return ANY_FAILURE;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ModelFailoverPolicy policy : values()) {
            if (policy.value.equals(normalized)) return policy;
        }
        throw new IllegalArgumentException("invalid failoverPolicy: " + value);
    }
}
