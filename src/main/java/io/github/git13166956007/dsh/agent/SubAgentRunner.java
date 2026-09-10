package io.github.git13166956007.dsh.agent;

import java.util.List;
import java.util.Set;

public final class SubAgentRunner {
    private final AgentLoop agentLoop;
    private final SubAgentProfileRegistry profiles;

    public SubAgentRunner(AgentLoop agentLoop, SubAgentProfileRegistry profiles) {
        this.agentLoop = agentLoop;
        this.profiles = profiles;
    }

    public AgentRunResult run(String prompt, String apiKey, String profileId) throws Exception {
        SubAgentProfileData profile = profiles.resolve(profileId);
        AgentExecutionOptions options = new AgentExecutionOptions(profile.modelId(), profile.mode(),
                profile.systemPrompt(), profile.maxTurns(), Set.copyOf(profile.allowedToolNames()),
                Set.copyOf(profile.skillIds()));
        return agentLoop.runDetailed(prompt, apiKey, List.of(), options);
    }
}
