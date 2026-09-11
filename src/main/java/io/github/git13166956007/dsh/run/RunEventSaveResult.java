package io.github.git13166956007.dsh.run;

/** Result of an idempotent run-event write. */
public record RunEventSaveResult(RunEventData event, boolean inserted) {
    public RunEventSaveResult {
        if (event == null) throw new IllegalArgumentException("event must not be null");
    }
}
