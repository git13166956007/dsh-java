package io.github.git13166956007.dsh.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlanRegistry {
    private final PlanStore store;
    private final Map<String, PlanData> plans = new LinkedHashMap<String, PlanData>();
    private final Map<String, List<PlanStepData>> steps = new LinkedHashMap<String, List<PlanStepData>>();

    public PlanRegistry(PlanStore store) {
        this.store = store;
        try {
            for (PlanData plan : store.listPlans()) {
                plans.put(plan.id(), plan);
                steps.put(plan.id(), new ArrayList<PlanStepData>(store.listSteps(plan.id())));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load plans", exception);
        }
    }

    public synchronized List<Plan> list() {
        return plans.values().stream().map(this::view).toList();
    }

    public synchronized Plan find(String id) {
        PlanData plan = plans.get(id);
        return plan == null ? null : view(plan);
    }

    public synchronized Plan create(String title, String goal, String agentId, String modelId,
                                    boolean approvalRequired, List<PlanStepInput> inputs) {
        String planId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        PlanStatus status = approvalRequired ? PlanStatus.DRAFT : PlanStatus.APPROVED;
        PlanData plan = new PlanData(planId, required(title, "title"), required(goal, "goal"),
                blankToNull(agentId), blankToNull(modelId), approvalRequired, status, now, now);
        List<PlanStepData> planSteps = new ArrayList<PlanStepData>();
        if (inputs == null || inputs.isEmpty()) throw new IllegalArgumentException("steps must not be empty");
        int stepNo = 1;
        for (PlanStepInput input : inputs) {
            if (input == null) throw new IllegalArgumentException("steps must not contain null");
            int maxAttempts = input.maxAttempts() == null ? 1 : input.maxAttempts();
            if (maxAttempts < 1 || maxAttempts > 10) throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
            planSteps.add(new PlanStepData(UUID.randomUUID().toString(), planId, stepNo++,
                    required(input.title(), "step.title"), required(input.instruction(), "step.instruction"),
                    PlanStepStatus.PENDING, null, 0, maxAttempts));
        }
        savePlan(plan, planSteps);
        return view(plan);
    }

    public synchronized Plan approve(String id) {
        PlanData plan = require(id);
        if (plan.status() != PlanStatus.DRAFT) throw new IllegalStateException("plan is not awaiting approval: " + id);
        return savePlan(withStatus(plan, PlanStatus.APPROVED));
    }

    public synchronized Plan start(String id) {
        PlanData plan = require(id);
        if (plan.status() != PlanStatus.APPROVED) {
            throw new IllegalStateException("plan must be approved before execution: " + id);
        }
        return savePlan(withStatus(plan, PlanStatus.RUNNING));
    }

    public synchronized Plan startStep(String planId, String stepId) {
        PlanData plan = require(planId);
        List<PlanStepData> planSteps = steps.get(planId);
        PlanStepData step = findStep(planSteps, stepId);
        PlanStepData updated = new PlanStepData(step.id(), step.planId(), step.stepNo(), step.title(), step.instruction(),
                PlanStepStatus.RUNNING, step.result(), step.attempts() + 1, step.maxAttempts());
        replaceStep(planSteps, updated);
        saveStep(updated);
        return view(plan);
    }

    public synchronized Plan completeStep(String planId, String stepId, String result) {
        PlanData plan = require(planId);
        List<PlanStepData> planSteps = steps.get(planId);
        PlanStepData step = findStep(planSteps, stepId);
        PlanStepData updated = new PlanStepData(step.id(), step.planId(), step.stepNo(), step.title(), step.instruction(),
                PlanStepStatus.COMPLETED, result, step.attempts(), step.maxAttempts());
        replaceStep(planSteps, updated);
        saveStep(updated);
        return view(plan);
    }

    public synchronized Plan failStep(String planId, String stepId, String result) {
        PlanData plan = require(planId);
        List<PlanStepData> planSteps = steps.get(planId);
        PlanStepData step = findStep(planSteps, stepId);
        PlanStepData updated = new PlanStepData(step.id(), step.planId(), step.stepNo(), step.title(), step.instruction(),
                PlanStepStatus.FAILED, result, step.attempts(), step.maxAttempts());
        replaceStep(planSteps, updated);
        saveStep(updated);
        return savePlan(withStatus(plan, PlanStatus.FAILED));
    }

    public synchronized Plan complete(String id) {
        return savePlan(withStatus(require(id), PlanStatus.COMPLETED));
    }

    public synchronized Plan cancel(String id) {
        PlanData plan = require(id);
        if (plan.status().terminal()) return view(plan);
        List<PlanStepData> planSteps = steps.get(id);
        for (int index = 0; index < planSteps.size(); index++) {
            PlanStepData step = planSteps.get(index);
            if (step.status() == PlanStepStatus.PENDING || step.status() == PlanStepStatus.RUNNING) {
                PlanStepData cancelled = new PlanStepData(step.id(), step.planId(), step.stepNo(), step.title(),
                        step.instruction(), PlanStepStatus.CANCELLED, step.result(), step.attempts(), step.maxAttempts());
                planSteps.set(index, cancelled);
                saveStep(cancelled);
            }
        }
        return savePlan(withStatus(plan, PlanStatus.CANCELLED));
    }

    public synchronized boolean delete(String id) {
        if (!plans.containsKey(id)) return false;
        try {
            store.deletePlan(id);
            plans.remove(id);
            steps.remove(id);
            return true;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to delete plan", exception);
        }
    }

    public synchronized PlanStepData step(String planId, String stepId) {
        return findStep(steps.get(planId), stepId);
    }

    private Plan view(PlanData plan) {
        return Plan.from(plan, steps.getOrDefault(plan.id(), List.of()).stream()
                .sorted(Comparator.comparingInt(PlanStepData::stepNo)).toList());
    }

    private Plan savePlan(PlanData plan) {
        try {
            store.savePlan(plan);
            plans.put(plan.id(), plan);
            return view(plan);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to save plan", exception);
        }
    }

    private void savePlan(PlanData plan, List<PlanStepData> planSteps) {
        try {
            store.savePlan(plan);
            plans.put(plan.id(), plan);
            steps.put(plan.id(), planSteps);
            for (PlanStepData step : planSteps) store.saveStep(step);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to save plan", exception);
        }
    }

    private void saveStep(PlanStepData step) {
        try {
            store.saveStep(step);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to save plan step", exception);
        }
    }

    private static PlanData withStatus(PlanData plan, PlanStatus status) {
        return new PlanData(plan.id(), plan.title(), plan.goal(), plan.agentId(), plan.modelId(),
                plan.approvalRequired(), status, plan.createdAt(), Instant.now());
    }

    private static void replaceStep(List<PlanStepData> planSteps, PlanStepData updated) {
        for (int index = 0; index < planSteps.size(); index++) {
            if (planSteps.get(index).id().equals(updated.id())) {
                planSteps.set(index, updated);
                return;
            }
        }
        throw new IllegalArgumentException("unknown plan step: " + updated.id());
    }

    private static PlanStepData findStep(List<PlanStepData> planSteps, String stepId) {
        if (planSteps == null) throw new IllegalArgumentException("unknown plan: " + stepId);
        return planSteps.stream().filter(step -> step.id().equals(stepId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown plan step: " + stepId));
    }

    private PlanData require(String id) {
        PlanData plan = plans.get(id);
        if (plan == null) throw new IllegalArgumentException("unknown plan: " + id);
        return plan;
    }

    private static String required(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public record PlanStepInput(String title, String instruction, Integer maxAttempts) {
    }
}
