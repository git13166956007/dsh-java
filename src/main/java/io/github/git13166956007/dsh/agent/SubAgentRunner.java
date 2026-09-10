package io.github.git13166956007.dsh.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import io.github.git13166956007.dsh.run.RunKind;
import io.github.git13166956007.dsh.run.Run;
import io.github.git13166956007.dsh.run.RunManager;

public final class SubAgentRunner {
    private final AgentLoop agentLoop;
    private final SubAgentProfileRegistry profiles;
    private final RunManager runs;
    private final Map<String, String> reservations = new ConcurrentHashMap<String, String>();

    public SubAgentRunner(AgentLoop agentLoop, SubAgentProfileRegistry profiles) {
        this(agentLoop, profiles, null);
    }

    public SubAgentRunner(AgentLoop agentLoop, SubAgentProfileRegistry profiles, RunManager runs) {
        this.agentLoop = agentLoop;
        this.profiles = profiles;
        this.runs = runs;
    }

    public AgentRunResult run(String prompt, String apiKey, String profileId) throws Exception {
        return run(prompt, apiKey, profileId, null);
    }

    public AgentRunResult runForExecution(String prompt, String apiKey, String profileId) throws Exception {
        return run(prompt, apiKey, profileId, AgentMode.EXECUTION);
    }

    public AgentRunResult runForExecution(String prompt, String apiKey, String profileId,
                                          String parentRunId, String planId, String stepId) throws Exception {
        return run(prompt, apiKey, profileId, AgentMode.EXECUTION, parentRunId, planId, stepId);
    }

    /** Starts an independent execution worker and returns before its model call finishes. */
    public AgentRunHandle startForExecution(String prompt, String apiKey, String profileId) throws Exception {
        return startForExecution(prompt, apiKey, profileId, null, null, null);
    }

    /** Starts an execution worker with an optional durable parent run. */
    public AgentRunHandle startForExecution(String prompt, String apiKey, String profileId,
                                            String parentRunId, String planId, String stepId) throws Exception {
        SubAgentProfileData profile = profiles.resolve(profileId);
        AgentExecutionOptions options = executionOptions(profile, AgentMode.EXECUTION);
        AgentRunContext context = AgentRunContext.child(parentRunId, RunKind.SUB_AGENT, null, planId, stepId, profileId);
        String reservation = reserve(profile);
        try {
            AgentRunHandle handle = agentLoop.runAsync(prompt, apiKey, List.of(), options, context);
            bind(reservation, profile.id(), handle.runId());
            handle.result().whenComplete((result, error) -> finishReservation(handle.runId(), result, error));
            return handle;
        } catch (Exception exception) {
            releaseReservation(reservation, profile.id());
            throw exception;
        }
    }

    AgentRunHandle startForExecution(String prompt, String apiKey, String profileId,
                                     List<ChatMessage> history, AgentRunContext context) throws Exception {
        SubAgentProfileData profile = profiles.resolve(profileId);
        String reservation = reserve(profile);
        try {
            AgentRunHandle handle = agentLoop.runAsync(prompt, apiKey, history,
                    executionOptions(profile, AgentMode.EXECUTION), context);
            bind(reservation, profile.id(), handle.runId());
            handle.result().whenComplete((result, error) -> finishReservation(handle.runId(), result, error));
            return handle;
        } catch (Exception exception) {
            releaseReservation(reservation, profile.id());
            throw exception;
        }
    }

    AgentRunHandle resumeForExecution(String runId, String prompt, String apiKey, String profileId,
                                      List<ChatMessage> history, AgentRunContext context) throws Exception {
        SubAgentProfileData profile = profiles.resolve(profileId);
        trackRecoveredRun(runId, profile.id());
        AgentRunHandle handle = agentLoop.resumeAsync(runId, prompt, apiKey, history,
                executionOptions(profile, AgentMode.EXECUTION), context);
        handle.result().whenComplete((result, error) -> finishReservation(handle.runId(), result, error));
        return handle;
    }

    boolean cancel(String runId) {
        return agentLoop.cancel(runId);
    }

    public List<SubAgentProfile> delegableProfiles() {
        return profiles.delegableProfiles();
    }

    public boolean canDelegate(String profileId) {
        return delegableProfiles().stream().anyMatch(profile -> profile.id().equals(profileId));
    }

    public void trackRecoveredRun(String runId, String profileId) {
        if (runId == null || profileId == null) return;
        releaseCompletedReservations();
        if (!reservations.containsKey(runId)) {
            profiles.reserve(profileId, runId);
            reservations.put(runId, profileId);
        }
    }

    public void releaseCompletedReservations() {
        if (runs == null) return;
        for (Map.Entry<String, String> entry : reservations.entrySet()) {
            String reservationId = entry.getKey();
            if (reservationId.startsWith("reservation:")) continue;
            try {
                Run run = runs.find(reservationId);
                if (run == null || run.status().terminal()) releaseReservation(reservationId, entry.getValue());
            } catch (Exception ignored) {
                // Capacity accounting must not break a running agent when telemetry is unavailable.
            }
        }
    }

    private AgentRunResult run(String prompt, String apiKey, String profileId, AgentMode modeOverride) throws Exception {
        return run(prompt, apiKey, profileId, modeOverride, null, null, null);
    }

    private AgentRunResult run(String prompt, String apiKey, String profileId, AgentMode modeOverride,
                               String parentRunId, String planId, String stepId) throws Exception {
        SubAgentProfileData profile = profiles.resolve(profileId);
        AgentExecutionOptions options = executionOptions(profile, modeOverride);
        AgentRunContext context = parentRunId == null ? AgentRunContext.standalone()
                : AgentRunContext.child(parentRunId, RunKind.SUB_AGENT, null, planId, stepId, profileId);
        String reservation = reserve(profile);
        try {
            AgentRunResult result = agentLoop.runDetailed(prompt, apiKey, List.of(), options, context);
            if (result.pendingApproval() != null && result.runId() != null) {
                bind(reservation, profile.id(), result.runId());
            } else {
                releaseReservation(reservation, profile.id());
            }
            return result;
        } catch (Exception exception) {
            releaseReservation(reservation, profile.id());
            throw exception;
        }
    }

    private synchronized String reserve(SubAgentProfileData profile) throws Exception {
        releaseCompletedReservations();
        if (runs != null) {
            long persisted = runs.list().stream()
                    .filter(run -> profile.id().equals(run.agentId()) && !run.status().terminal())
                    .filter(run -> !reservations.containsKey(run.id()))
                    .count();
            if (persisted + profiles.activeRunCount(profile.id()) >= profile.maxConcurrentRuns()) {
                throw new IllegalStateException("sub-agent profile concurrency limit reached: " + profile.id());
            }
        }
        String reservation = "reservation:" + UUID.randomUUID();
        profiles.reserve(profile.id(), reservation);
        reservations.put(reservation, profile.id());
        return reservation;
    }

    private void bind(String reservation, String profileId, String runId) {
        if (runId == null || runId.isBlank()) return;
        profiles.release(profileId, reservation);
        reservations.remove(reservation, profileId);
        profiles.reserve(profileId, runId);
        reservations.put(runId, profileId);
    }

    private void finishReservation(String runId, AgentRunResult result, Throwable error) {
        if (result != null && result.pendingApproval() != null) return;
        String profileId = reservations.get(runId);
        if (profileId != null) releaseReservation(runId, profileId);
        releaseCompletedReservations();
    }

    private void releaseReservation(String reservationId, String profileId) {
        profiles.release(profileId, reservationId);
        reservations.remove(reservationId, profileId);
    }

    private static AgentExecutionOptions executionOptions(SubAgentProfileData profile, AgentMode modeOverride) {
        return new AgentExecutionOptions(profile.modelId(), modeOverride == null ? profile.mode() : modeOverride,
                profile.systemPrompt(), profile.maxTurns(), Set.copyOf(profile.allowedToolNames()),
                Set.copyOf(profile.skillIds()), profile.maxToolCalls(), profile.timeoutSeconds(), profile.maxDepth());
    }
}
