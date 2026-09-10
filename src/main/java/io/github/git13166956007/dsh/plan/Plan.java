package io.github.git13166956007.dsh.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record Plan(
        String id,
        String title,
        String goal,
        String agentId,
        String modelId,
        boolean approvalRequired,
        int maxConcurrency,
        PlanStatus status,
        Instant createdAt,
        Instant updatedAt,
        List<PlanStep> steps) {
    public Plan {
        steps = Collections.unmodifiableList(new ArrayList<PlanStep>(steps));
    }

    static Plan from(PlanData data, List<PlanStepData> steps) {
        return new Plan(data.id(), data.title(), data.goal(), data.agentId(), data.modelId(),
                data.approvalRequired(), data.maxConcurrency(), data.status(), data.createdAt(), data.updatedAt(),
                steps.stream().map(PlanStep::from).toList());
    }
}
