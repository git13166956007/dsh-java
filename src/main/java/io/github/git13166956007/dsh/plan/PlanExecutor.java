package io.github.git13166956007.dsh.plan;

import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentMode;
import io.github.git13166956007.dsh.agent.SubAgentRunner;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
            for (PlanStep step : plan.steps()) {
                if (cancelled.contains(id)) {
                    plans.cancel(id);
                    return;
                }
                boolean completed = false;
                while (!completed) {
                    if (cancelled.contains(id)) {
                        plans.cancel(id);
                        return;
                    }
                    plans.startStep(id, step.id());
                    try {
                        Plan current = plans.find(id);
                        String result = step.subAgentId() == null
                                ? agentLoop.runDetailed(step.instruction(), apiKey, List.of(), current.modelId(),
                                current.agentId(), AgentMode.EXECUTION).answer()
                                : subAgents.runForExecution(step.instruction(), apiKey, step.subAgentId()).answer();
                        plans.completeStep(id, step.id(), result);
                        completed = true;
                    } catch (Exception exception) {
                        PlanStep latest = plans.find(id).steps().stream()
                                .filter(candidate -> candidate.id().equals(step.id())).findFirst().orElseThrow();
                        if (latest.attempts() < latest.maxAttempts()) continue;
                        plans.failStep(id, step.id(), exception.getMessage());
                        return;
                    }
                }
            }
            if (!cancelled.contains(id)) plans.complete(id);
        } finally {
            cancelled.remove(id);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
