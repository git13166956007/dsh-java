package io.github.git13166956007.dsh.run;

import java.time.Instant;

record RunEventData(long id, String runId, String eventKey, String type, String payload, Instant createdAt) {
    RunEventData(long id, String runId, String type, String payload, Instant createdAt) {
        this(id, runId, null, type, payload, createdAt);
    }
}
