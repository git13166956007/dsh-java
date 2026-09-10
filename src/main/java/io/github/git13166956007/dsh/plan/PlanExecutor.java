package io.github.git13166956007.dsh.plan;

import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentMode;
import io.github.git13166956007.dsh.agent.SubAgentRunner;
import io.github.git13166956007.dsh.agent.AgentRunContext;
import io.github.git13166956007.dsh.run.RunKind;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.run.RunSpec;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class PlanExecutor implements AutoCloseable {
    private final PlanRegistry plans;
    private final AgentLoop agentLoop;
    private final SubAgentRunner subAgents;
    private final RunManager runs;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final Map<String, PendingPlan> pendingApprovals = new ConcurrentHashMap<String, PendingPlan>();

    public PlanExecutor(PlanRegistry plans, AgentLoop agentLoop, SubAgentRunner subAgents) {
        this(plans, agentLoop, subAgents, null);
    }

    public PlanExecutor(PlanRegistry plans, AgentLoop agentLoop, SubAgentRunner subAgents, RunManager runs) {
        this.plans = plans;
        this.agentLoop = agentLoop;
        this.subAgents = subAgents;
        this.runs = runs;
    }

    public Plan execute(String id, String apiKey) {
        Plan plan = plans.start(id);
        String runId = startRun(plan);
        executor.submit(() -> run(plan.id(), apiKey, runId));
        return plan;
    }

    public Plan cancel(String id) {
        cancelled.add(id);
        pendingApprovals.entrySet().removeIf(entry -> {
            if (!entry.getValue().planId().equals(id)) return false;
            agentLoop.cancelPendingApproval(entry.getKey());
            if (runs != null) cancelRun(entry.getKey());
            return true;
        });
        return plans.cancel(id);
    }

    private void run(String id, String apiKey, String runId) {
        Plan plan = plans.find(id);
        if (plan == null) return;
        try {
            CompletionService<StepOutcome> completions = new ExecutorCompletionService<StepOutcome>(executor);
            Map<String, Future<StepOutcome>> running = new HashMap<String, Future<StepOutcome>>();
            Set<String> pending = new HashSet<String>();
            for (PlanStep step : plan.steps()) {
                if (step.status() != PlanStepStatus.COMPLETED && step.status() != PlanStepStatus.CANCELLED) {
                    pending.add(step.id());
                }
            }

            while (!pending.isEmpty() || !running.isEmpty()) {
                if (cancelled.contains(id)) {
                    running.values().forEach(future -> future.cancel(true));
                    plans.cancel(id);
                    cancelRun(runId);
                    return;
                }

                Plan current = plans.find(id);
                for (PlanStep step : current.steps()) {
                    if (running.size() >= current.maxConcurrency() || !pending.contains(step.id()) || !ready(step, current)) continue;
                    pending.remove(step.id());
                    plans.startStep(id, step.id());
                    running.put(step.id(), completions.submit(() -> executeStep(id, step, apiKey, runId)));
                }

                if (running.isEmpty()) {
                    plans.failStep(id, firstPendingStep(current, pending).id(), "dependency deadlock");
                    failRun(runId, "dependency deadlock");
                    return;
                }

                StepOutcome outcome = completions.take().get();
                running.remove(outcome.stepId());
                if (outcome.waitingApproval()) {
                    plans.waitStepForApproval(id, outcome.stepId(), outcome.result());
                    plans.waitForApproval(id);
                    pendingApprovals.put(outcome.approvalRunId(), new PendingPlan(id, apiKey, runId, outcome.stepId()));
                    event(runId, "plan_approval_required", outcome.approvalRunId());
                    return;
                }
                if (!outcome.success()) {
                    running.values().forEach(future -> future.cancel(true));
                    plans.failStep(id, outcome.stepId(), outcome.result());
                    failRun(runId, outcome.result());
                    return;
                }
                event(runId, "plan_step_completed", outcome.stepId() + " " + outcome.result());
                plans.completeStep(id, outcome.stepId(), outcome.result());
            }
            if (!cancelled.contains(id)) {
                plans.complete(id);
                completeRun(runId, "plan completed");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plans.cancel(id);
            cancelRun(runId);
        } catch (Exception exception) {
            Plan current = plans.find(id);
            if (current != null && !current.status().terminal()) plans.cancel(id);
            failRun(runId, exception.getMessage());
        } finally {
            cancelled.remove(id);
        }
    }

    private StepOutcome executeStep(String planId, PlanStep step, String apiKey, String parentRunId) {
        String stepRunId = startStepRun(planId, step, parentRunId);
        while (true) {
            try {
                Plan current = plans.find(planId);
                io.github.git13166956007.dsh.agent.AgentRunResult result = step.subAgentId() == null
                        ? agentLoop.runDetailed(step.instruction(), apiKey, List.of(), current.modelId(),
                        current.agentId(), AgentMode.EXECUTION, null, null,
                        AgentRunContext.child(stepRunId, RunKind.AGENT, null, planId, step.id(), current.agentId()))
                        : subAgents.runForExecution(step.instruction(), apiKey, step.subAgentId(), stepRunId,
                        planId, step.id());
                if (result.pendingApproval() != null) {
                    return new StepOutcome(step.id(), false, true, result.answer(), result.runId());
                }
                completeRun(stepRunId, result.answer());
                return new StepOutcome(step.id(), true, false, result.answer(), null);
            } catch (Exception exception) {
                PlanStep latest = plans.find(planId).steps().stream()
                        .filter(candidate -> candidate.id().equals(step.id())).findFirst().orElseThrow();
                if (latest.attempts() >= latest.maxAttempts()) {
                    failRun(stepRunId, exception.getMessage());
                    return new StepOutcome(step.id(), false, false, exception.getMessage(), null);
                }
                event(stepRunId, "step_retry", exception.getMessage());
                plans.startStep(planId, step.id());
            }
        }
    }

    private String startRun(Plan plan) {
        if (runs == null) return null;
        try {
            return runs.start(new RunSpec(null, RunKind.PLAN, null, plan.id(), null, plan.agentId(), plan.modelId()));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to start plan run", exception);
        }
    }

    private String startStepRun(String planId, PlanStep step, String parentRunId) {
        if (runs == null) return null;
        try {
            runs.event(parentRunId, "plan_step_started", step.id());
            return runs.start(new RunSpec(parentRunId, RunKind.PLAN_STEP, null, planId, step.id(),
                    step.subAgentId(), null));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to start plan step run", exception);
        }
    }

    private void event(String runId, String type, String payload) {
        if (runId == null) return;
        try {
            runs.event(runId, type, payload);
        } catch (Exception ignored) {
        }
    }

    private void completeRun(String runId, String output) {
        if (runId == null) return;
        try {
            runs.complete(runId, output);
        } catch (Exception ignored) {
        }
    }

    private void failRun(String runId, String error) {
        if (runId == null) return;
        try {
            runs.fail(runId, error);
        } catch (Exception ignored) {
        }
    }

    private void cancelRun(String runId) {
        if (runId == null) return;
        try {
            runs.cancel(runId);
        } catch (Exception ignored) {
        }
    }

    public io.github.git13166956007.dsh.agent.AgentRunResult resumeApproval(String approvalRunId, boolean approved) throws Exception {
        PendingPlan pending = pendingApprovals.get(approvalRunId);
        if (pending == null) throw new IllegalArgumentException("plan approval is not pending: " + approvalRunId);
        Plan planBeforeResume = plans.find(pending.planId());
        if (planBeforeResume == null || planBeforeResume.status() == PlanStatus.CANCELLED) {
            throw new IllegalStateException("plan is no longer active: " + pending.planId());
        }
        io.github.git13166956007.dsh.agent.AgentRunResult result = agentLoop.resumeApproval(approvalRunId, approved);
        if (result.pendingApproval() != null) {
            pendingApprovals.remove(approvalRunId);
            pendingApprovals.put(result.runId(), pending);
            return result;
        }
        pendingApprovals.remove(approvalRunId);
        io.github.git13166956007.dsh.run.Run child = runs == null ? null : runs.find(approvalRunId);
        if (child != null && child.parentRunId() != null) completeRun(child.parentRunId(), result.answer());
        Plan plan = plans.find(pending.planId());
        if (plan != null) {
            String stepId = child == null ? pending.stepId() : child.stepId();
            PlanStep step = plan.steps().stream().filter(candidate -> candidate.id().equals(stepId))
                    .findFirst().orElse(null);
            if (step != null) plans.completeStep(plan.id(), step.id(), result.answer());
            plans.resume(plan.id());
            executor.submit(() -> run(plan.id(), pending.apiKey(), pending.planRunId()));
        }
        return result;
    }

    private static boolean ready(PlanStep step, Plan plan) {
        for (Integer dependency : step.dependsOn()) {
            PlanStep dependencyStep = plan.steps().stream().filter(candidate -> candidate.stepNo() == dependency)
                    .findFirst().orElse(null);
            if (dependencyStep == null || dependencyStep.status() != PlanStepStatus.COMPLETED) return false;
        }
        return true;
    }

    private static PlanStep firstPendingStep(Plan plan, Set<String> pending) {
        return plan.steps().stream().filter(step -> pending.contains(step.id())).findFirst()
                .orElseThrow(() -> new IllegalStateException("no pending plan step"));
    }

    private record StepOutcome(String stepId, boolean success, boolean waitingApproval, String result, String approvalRunId) {
    }

    private record PendingPlan(String planId, String apiKey, String planRunId, String stepId) {
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
