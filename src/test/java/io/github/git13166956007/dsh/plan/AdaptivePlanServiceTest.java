package io.github.git13166956007.dsh.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentMode;
import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.InMemorySubAgentProfileStore;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.agent.SubAgentProfileRegistry;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AdaptivePlanServiceTest {
    @Test
    void convertsPlanningJsonIntoAnApprovedPlanAndSelectsAnExecutionWorker() throws Exception {
        ChatModel model = new ChatModel() {
            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                return new ModelResponse("{\"title\":\"Research task\",\"goal\":\"Inspect the repository\","
                        + "\"steps\":[{\"title\":\"Inspect\",\"instruction\":\"Research the repository files\"}]}",
                        List.of(), "stop");
            }
        };
        SubAgentProfileRegistry subAgents = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);
        subAgents.create("Research worker", AgentMode.EXECUTION, null, "Research repository files", 4,
                List.of(), List.of(), true);
        AdaptivePlanService service = new AdaptivePlanService(new AgentLoop(model, new ToolRegistry(), 2),
                new PlanRegistry(new InMemoryPlanStore()), subAgents, new ObjectMapper());

        Plan plan = service.create("Inspect the repository", null, null, null, false, 4);

        assertEquals(PlanStatus.APPROVED, plan.status());
        assertEquals(1, plan.steps().size());
        assertEquals("Research worker", subAgents.find(plan.steps().get(0).subAgentId()).name());
    }
}
