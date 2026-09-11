package io.github.git13166956007.dsh.event;

public record EventOutcome<T>(T value, boolean accepted, String reason) {
    public static <T> EventOutcome<T> accept(T value) { return new EventOutcome<T>(value, true, null); }
    public static <T> EventOutcome<T> reject(String reason) {
        return new EventOutcome<T>(null, false, reason == null ? "event rejected" : reason);
    }
}
