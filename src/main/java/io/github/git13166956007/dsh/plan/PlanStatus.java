package io.github.git13166956007.dsh.plan;

import java.util.Locale;

public enum PlanStatus {
    DRAFT("draft"),
    APPROVED("approved"),
    RUNNING("running"),
    WAITING_APPROVAL("waiting_approval"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String value;

    PlanStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static PlanStatus parse(String value) {
        if (value == null) throw new IllegalArgumentException("plan status must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (PlanStatus status : values()) {
            if (status.value.equals(normalized) || status.name().equalsIgnoreCase(normalized)) return status;
        }
        throw new IllegalArgumentException("unsupported plan status: " + value);
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
