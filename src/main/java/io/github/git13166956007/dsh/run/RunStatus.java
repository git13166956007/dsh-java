package io.github.git13166956007.dsh.run;

public enum RunStatus {
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String value;

    RunStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static RunStatus parse(String value) {
        if (value == null) throw new IllegalArgumentException("run status must not be null");
        for (RunStatus status : values()) {
            if (status.value.equalsIgnoreCase(value.trim()) || status.name().equalsIgnoreCase(value.trim())) return status;
        }
        throw new IllegalArgumentException("unsupported run status: " + value);
    }

    public boolean terminal() {
        return this != RUNNING;
    }
}
