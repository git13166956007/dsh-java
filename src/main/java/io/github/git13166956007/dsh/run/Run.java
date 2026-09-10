package io.github.git13166956007.dsh.run;

import java.time.Instant;

public record Run(
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
    static Run from(RunData data) {
        return new Run(data.id(), data.parentRunId(), data.kind(), data.status(), data.conversationId(), data.planId(),
                data.stepId(), data.agentId(), data.modelId(), data.startedAt(), data.completedAt(), data.error(), data.output());
    }
}
