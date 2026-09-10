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

        Plan plan = service.create("Inspect the repository", null, null, null, false, 4, 1);

        assertEquals(PlanStatus.APPROVED, plan.status());
        assertEquals(1, plan.steps().size());
        assertEquals("Research worker", subAgents.find(plan.steps().get(0).subAgentId()).name());
    }

    @Test
    void canCreateAndPersistAWorkerWhenNoExistingWorkerMatches() throws Exception {
        ChatModel model = new ChatModel() {
            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                return new ModelResponse("{" +
                        "\"title\":\"Deploy task\",\"goal\":\"Deploy safely\",\"steps\":[{" +
                        "\"title\":\"Deploy\",\"instruction\":\"Deploy the service\",\"worker\":{" +
                        "\"name\":\"Deployment worker\",\"systemPrompt\":\"Deploy and verify\",\"allowedToolNames\":[\"missing\"],\"skillIds\":[]}}]}",
                        List.of(), "stop");
            }
        };
        ToolRegistry tools = new ToolRegistry();
        SubAgentProfileRegistry subAgents = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);
        AdaptivePlanService service = new AdaptivePlanService(new AgentLoop(model, tools, 2),
                new PlanRegistry(new InMemoryPlanStore()), subAgents, new ObjectMapper(), tools, null);

        Plan plan = service.create("Deploy the service", null, null, "model-1", false, 4, 1, true);

        assertEquals("Deployment worker", subAgents.find(plan.steps().get(0).subAgentId()).name());
        assertEquals(List.of(), subAgents.find(plan.steps().get(0).subAgentId()).allowedToolNames());
    }

    @Test
    void preservesDependenciesGeneratedByThePlanningAgent() throws Exception {
        ChatModel model = (messages, definitions) -> new ModelResponse(
                "{\"title\":\"Build task\",\"goal\":\"Build and verify\",\"steps\":["
                        + "{\"title\":\"Build\",\"instruction\":\"Build the project\",\"dependsOn\":[]},"
                        + "{\"title\":\"Verify\",\"instruction\":\"Verify the build\",\"dependsOn\":[1]}]}",
                List.of(), "stop");
        Plan plan = new AdaptivePlanService(new AgentLoop(model, new ToolRegistry(), 2),
                new PlanRegistry(new InMemoryPlanStore()),
                new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8), new ObjectMapper())
                .create("Build and verify", null, null, null, false, 4, 2);

        assertEquals(List.of(1), plan.steps().get(1).dependsOn());
    }

    @Test
    void selectsWorkersForChineseTasks() throws Exception {
        ChatModel model = (messages, definitions) -> new ModelResponse(
                "{\"title\":\"迁移任务\",\"goal\":\"完成数据库迁移\",\"steps\":["
                        + "{\"title\":\"检查数据库\",\"instruction\":\"检查数据库迁移状态\"}]}",
                List.of(), "stop");
        SubAgentProfileRegistry subAgents = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);
        subAgents.create("数据库迁移 Worker", AgentMode.EXECUTION, null, "负责数据库迁移检查", 4,
                List.of(), List.of(), true);
        Plan plan = new AdaptivePlanService(new AgentLoop(model, new ToolRegistry(), 2),
                new PlanRegistry(new InMemoryPlanStore()), subAgents, new ObjectMapper())
                .create("执行数据库迁移", null, null, null, false, 4, 1);

        assertEquals("数据库迁移 Worker", subAgents.find(plan.steps().get(0).subAgentId()).name());
    }
}
