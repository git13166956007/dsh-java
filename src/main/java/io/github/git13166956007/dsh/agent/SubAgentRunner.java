package io.github.git13166956007.dsh.agent;

import java.util.List;
import java.util.Set;
import io.github.git13166956007.dsh.run.RunKind;

public final class SubAgentRunner {
    private final AgentLoop agentLoop;
    private final SubAgentProfileRegistry profiles;

    public SubAgentRunner(AgentLoop agentLoop, SubAgentProfileRegistry profiles) {
        this.agentLoop = agentLoop;
        this.profiles = profiles;
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

    public List<SubAgentProfile> delegableProfiles() {
        return profiles.delegableProfiles();
    }

    public boolean canDelegate(String profileId) {
        return delegableProfiles().stream().anyMatch(profile -> profile.id().equals(profileId));
    }

    private AgentRunResult run(String prompt, String apiKey, String profileId, AgentMode modeOverride) throws Exception {
        return run(prompt, apiKey, profileId, modeOverride, null, null, null);
    }

    private AgentRunResult run(String prompt, String apiKey, String profileId, AgentMode modeOverride,
                               String parentRunId, String planId, String stepId) throws Exception {
        SubAgentProfileData profile = profiles.resolve(profileId);
        AgentExecutionOptions options = new AgentExecutionOptions(profile.modelId(), modeOverride == null ? profile.mode() : modeOverride,
                profile.systemPrompt(), profile.maxTurns(), Set.copyOf(profile.allowedToolNames()),
                Set.copyOf(profile.skillIds()), profile.maxToolCalls(), profile.timeoutSeconds(), profile.maxDepth());
        AgentRunContext context = parentRunId == null ? AgentRunContext.standalone()
                : AgentRunContext.child(parentRunId, RunKind.SUB_AGENT, null, planId, stepId, profileId);
        return agentLoop.runDetailed(prompt, apiKey, List.of(), options, context);
    }
}
