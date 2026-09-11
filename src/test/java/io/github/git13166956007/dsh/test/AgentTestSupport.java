package io.github.git13166956007.dsh.test;

import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentProfileRegistry;
import io.github.git13166956007.dsh.agent.AgentContinuationStore;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.context.ContextManager;
import io.github.git13166956007.dsh.memory.MemoryManager;
import io.github.git13166956007.dsh.plugin.DshServices;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.skill.SkillRegistry;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.core.scope.Scope;

/** Test-only bootstrap helper; production code must enter through a Scope. */
public final class AgentTestSupport {
    private AgentTestSupport() { }

    public static AgentLoop loop(ChatModel model, ToolRegistry tools, Object... values) {
        if (values == null || values.length == 0 || !(values[values.length - 1] instanceof Integer maxTurns)) {
            throw new IllegalArgumentException("test agent requires maxTurns as the last argument");
        }
        SkillRegistry skills = values.length > 1 ? (SkillRegistry) values[0] : null;
        AgentProfileRegistry profiles = values.length > 2 ? (AgentProfileRegistry) values[1] : null;
        MemoryManager memories = values.length > 3 ? (MemoryManager) values[2] : null;
        RunManager runs = values.length > 4 ? (RunManager) values[3] : null;
        AgentContinuationStore continuations = values.length > 5 ? (AgentContinuationStore) values[4] : null;
        ContextManager contexts = values.length > 7 ? (ContextManager) values[6] : null;
        if (model == null || tools == null) throw new IllegalArgumentException("model and tools are required");

        Scope scope = new Scope("test-agent");
        scope.provide(DshServices.CHAT_MODEL, model);
        scope.provide(DshServices.TOOLS, tools);
        if (skills != null) scope.provide(DshServices.SKILLS, skills);
        if (profiles != null) scope.provide(DshServices.AGENTS, profiles);
        if (memories != null) scope.provide(DshServices.MEMORIES, memories);
        if (runs != null) scope.provide(DshServices.RUNS, runs);
        if (continuations != null) scope.provide(DshServices.CONTINUATIONS, continuations);
        if (contexts != null) scope.provide(DshServices.CONTEXT, contexts);
        return new AgentLoop(scope, maxTurns);
    }
}
