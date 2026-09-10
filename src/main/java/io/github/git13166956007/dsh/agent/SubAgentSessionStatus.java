package io.github.git13166956007.dsh.agent;

public enum SubAgentSessionStatus {
    OPEN("open"),
    CLOSED("closed");

    private final String value;

    SubAgentSessionStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SubAgentSessionStatus parse(String value) {
        if (value == null) throw new IllegalArgumentException("session status must not be null");
        for (SubAgentSessionStatus status : values()) {
            if (status.value.equalsIgnoreCase(value.trim()) || status.name().equalsIgnoreCase(value.trim())) return status;
        }
        throw new IllegalArgumentException("unsupported session status: " + value);
    }
}
