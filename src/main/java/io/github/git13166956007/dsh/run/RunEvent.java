package io.github.git13166956007.dsh.run;

import java.time.Instant;

public record RunEvent(long id, String runId, String type, String payload, Instant createdAt) {
    static RunEvent from(RunEventData data) {
        return new RunEvent(data.id(), data.runId(), data.type(), data.payload(), data.createdAt());
    }
}
