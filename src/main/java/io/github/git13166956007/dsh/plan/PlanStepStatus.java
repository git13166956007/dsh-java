package io.github.git13166956007.dsh.plan;

import java.util.Locale;

public enum PlanStepStatus {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String value;

    PlanStepStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static PlanStepStatus parse(String value) {
        if (value == null) throw new IllegalArgumentException("plan step status must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (PlanStepStatus status : values()) {
            if (status.value.equals(normalized) || status.name().equalsIgnoreCase(normalized)) return status;
        }
        throw new IllegalArgumentException("unsupported plan step status: " + value);
    }
}
