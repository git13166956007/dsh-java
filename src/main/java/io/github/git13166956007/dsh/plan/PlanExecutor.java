package io.github.git13166956007.dsh.plan;

import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentMode;
import io.github.git13166956007.dsh.agent.SubAgentRunner;
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
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    public PlanExecutor(PlanRegistry plans, AgentLoop agentLoop, SubAgentRunner subAgents) {
        this.plans = plans;
        this.agentLoop = agentLoop;
        this.subAgents = subAgents;
    }

    public Plan execute(String id, String apiKey) {
        Plan plan = plans.start(id);
        executor.submit(() -> run(plan.id(), apiKey));
        return plan;
    }

    public Plan cancel(String id) {
        cancelled.add(id);
        return plans.cancel(id);
    }

    private void run(String id, String apiKey) {
        Plan plan = plans.find(id);
        if (plan == null) return;
        try {
            CompletionService<StepOutcome> completions = new ExecutorCompletionService<StepOutcome>(executor);
            Map<String, Future<StepOutcome>> running = new HashMap<String, Future<StepOutcome>>();
            Set<String> pending = new HashSet<String>();
            for (PlanStep step : plan.steps()) pending.add(step.id());

            while (!pending.isEmpty() || !running.isEmpty()) {
                if (cancelled.contains(id)) {
                    running.values().forEach(future -> future.cancel(true));
                    plans.cancel(id);
                    return;
                }

                Plan current = plans.find(id);
                for (PlanStep step : current.steps()) {
                    if (running.size() >= current.maxConcurrency() || !pending.contains(step.id()) || !ready(step, current)) continue;
                    pending.remove(step.id());
                    plans.startStep(id, step.id());
                    running.put(step.id(), completions.submit(() -> executeStep(id, step, apiKey)));
                }

                if (running.isEmpty()) {
                    plans.failStep(id, firstPendingStep(current, pending).id(), "dependency deadlock");
                    return;
                }

                StepOutcome outcome = completions.take().get();
                running.remove(outcome.stepId());
                if (!outcome.success()) {
                    running.values().forEach(future -> future.cancel(true));
                    plans.failStep(id, outcome.stepId(), outcome.result());
                    return;
                }
                plans.completeStep(id, outcome.stepId(), outcome.result());
            }
            if (!cancelled.contains(id)) plans.complete(id);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plans.cancel(id);
        } catch (Exception exception) {
            Plan current = plans.find(id);
            if (current != null && !current.status().terminal()) plans.cancel(id);
        } finally {
            cancelled.remove(id);
        }
    }

    private StepOutcome executeStep(String planId, PlanStep step, String apiKey) {
        while (true) {
            try {
                Plan current = plans.find(planId);
                String result = step.subAgentId() == null
                        ? agentLoop.runDetailed(step.instruction(), apiKey, List.of(), current.modelId(),
                        current.agentId(), AgentMode.EXECUTION).answer()
                        : subAgents.runForExecution(step.instruction(), apiKey, step.subAgentId()).answer();
                return new StepOutcome(step.id(), true, result);
            } catch (Exception exception) {
                PlanStep latest = plans.find(planId).steps().stream()
                        .filter(candidate -> candidate.id().equals(step.id())).findFirst().orElseThrow();
                if (latest.attempts() >= latest.maxAttempts()) {
                    return new StepOutcome(step.id(), false, exception.getMessage());
                }
                plans.startStep(planId, step.id());
            }
        }
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

    private record StepOutcome(String stepId, boolean success, String result) {
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
