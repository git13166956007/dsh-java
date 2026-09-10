package io.github.git13166956007.dsh.run;

import java.time.Instant;

record RunData(
        String id,
        String parentRunId,
        RunKind kind,
        RunStatus status,
        String conversationId,
        String planId,
        String stepId,
        String agentId,
        String modelId,
        Instant startedAt,
        Instant completedAt,
        String error,
        String output) {
}
