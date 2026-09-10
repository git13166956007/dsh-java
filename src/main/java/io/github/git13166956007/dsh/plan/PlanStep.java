package io.github.git13166956007.dsh.plan;

public record PlanStep(
        String id,
        int stepNo,
        String subAgentId,
        String title,
        String instruction,
        PlanStepStatus status,
        String result,
        int attempts,
        int maxAttempts) {
    static PlanStep from(PlanStepData data) {
        return new PlanStep(data.id(), data.stepNo(), data.subAgentId(), data.title(), data.instruction(), data.status(),
                data.result(), data.attempts(), data.maxAttempts());
    }
}
