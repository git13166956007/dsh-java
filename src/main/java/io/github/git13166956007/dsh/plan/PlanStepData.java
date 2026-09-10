package io.github.git13166956007.dsh.plan;

record PlanStepData(
        String id,
        String planId,
        int stepNo,
        String title,
        String instruction,
        PlanStepStatus status,
        String result,
        int attempts,
        int maxAttempts) {
}
