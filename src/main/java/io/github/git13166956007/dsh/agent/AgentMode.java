package io.github.git13166956007.dsh.agent;

import java.util.Locale;

public enum AgentMode {
    CHAT("chat", true),
    PLANNING("planning", false),
    EXECUTION("execution", true);

    private final String value;
    private final boolean toolsEnabled;

    AgentMode(String value, boolean toolsEnabled) {
        this.value = value;
        this.toolsEnabled = toolsEnabled;
    }

    public String value() {
        return value;
    }

    public boolean toolsEnabled() {
        return toolsEnabled;
    }

    public static AgentMode parse(String value) {
        if (value == null || value.trim().isEmpty()) return CHAT;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (AgentMode mode : values()) {
            if (mode.value.equals(normalized) || mode.name().equalsIgnoreCase(normalized)) return mode;
        }
        throw new IllegalArgumentException("unsupported agent mode: " + value);
    }
}
