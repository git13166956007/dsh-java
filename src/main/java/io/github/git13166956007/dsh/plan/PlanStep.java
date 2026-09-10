package io.github.git13166956007.dsh.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record PlanStep(
        String id,
        int stepNo,
        String subAgentId,
        List<Integer> dependsOn,
        String title,
        String instruction,
        PlanStepStatus status,
        String result,
        int attempts,
        int maxAttempts) {
    static PlanStep from(PlanStepData data) {
        return new PlanStep(data.id(), data.stepNo(), data.subAgentId(), data.dependsOn(), data.title(), data.instruction(), data.status(),
                data.result(), data.attempts(), data.maxAttempts());
    }

    public PlanStep {
        dependsOn = Collections.unmodifiableList(new ArrayList<Integer>(dependsOn));
    }
}
