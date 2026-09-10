package io.github.git13166956007.dsh.plan;

import java.util.List;

record PlanStepData(
        String id,
        String planId,
        int stepNo,
        String subAgentId,
        List<Integer> dependsOn,
        String title,
        String instruction,
        PlanStepStatus status,
        String result,
        int attempts,
        int maxAttempts) {
}
