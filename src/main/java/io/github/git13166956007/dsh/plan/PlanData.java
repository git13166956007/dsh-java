package io.github.git13166956007.dsh.plan;

import java.time.Instant;

record PlanData(
        String id,
        String title,
        String goal,
        String agentId,
        String modelId,
        boolean approvalRequired,
        PlanStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
