package io.github.git13166956007.dsh.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryPlanStore implements PlanStore {
    private final Map<String, PlanData> plans = new LinkedHashMap<String, PlanData>();
    private final Map<String, PlanStepData> steps = new LinkedHashMap<String, PlanStepData>();

    @Override
    public synchronized List<PlanData> listPlans() {
        return new ArrayList<PlanData>(plans.values());
    }

    @Override
    public synchronized List<PlanStepData> listSteps(String planId) {
        return steps.values().stream().filter(step -> step.planId().equals(planId))
                .sorted(java.util.Comparator.comparingInt(PlanStepData::stepNo)).toList();
    }

    @Override
    public synchronized void savePlan(PlanData plan) {
        plans.put(plan.id(), plan);
    }

    @Override
    public synchronized void saveStep(PlanStepData step) {
        steps.put(step.id(), step);
    }

    @Override
    public synchronized void deletePlan(String id) {
        plans.remove(id);
        steps.values().removeIf(step -> step.planId().equals(id));
    }
}
