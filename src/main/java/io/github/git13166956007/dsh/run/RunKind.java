package io.github.git13166956007.dsh.run;

import java.util.Locale;

public enum RunKind {
    CHAT("chat"),
    AGENT("agent"),
    PLAN("plan"),
    PLAN_STEP("plan_step"),
    SUB_AGENT("sub_agent");

    private final String value;

    RunKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static RunKind parse(String value) {
        if (value == null) throw new IllegalArgumentException("run kind must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (RunKind kind : values()) {
            if (kind.value.equals(normalized) || kind.name().equalsIgnoreCase(normalized)) return kind;
        }
        throw new IllegalArgumentException("unsupported run kind: " + value);
    }
}
