package io.github.git13166956007.dsh.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentMode;
import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.AgentStreamListener;
import io.github.git13166956007.dsh.agent.InMemorySubAgentProfileStore;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.agent.ModelStreamListener;
import io.github.git13166956007.dsh.agent.SubAgentProfileRegistry;
import io.github.git13166956007.dsh.model.InMemoryModelProfileStore;
import io.github.git13166956007.dsh.model.ModelRegistry;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.run.InMemoryRunStore;
import io.github.git13166956007.dsh.run.RunKind;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.run.RunSpec;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AdaptivePlanServiceTest {
    @Test
    void streamsPlanningOutputBeforeCreatingThePlan() throws Exception {
        ChatModel model = new ChatModel() {
            @Override
            public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> definitions) {
                return new ModelResponse("{\"title\":\"Streamed\",\"goal\":\"Verify\",\"steps\":[{\"title\":\"Check\",\"instruction\":\"Check it\"}]}",
                        List.of(), "stop");
            }

            @Override
            public ModelResponse stream(List<ChatMessage> messages, List<ToolDefinition> definitions,
                                        String apiKey, ModelStreamListener listener) {
                listener.onText("{\"title\":\"Streamed\",");
                listener.onText("\"goal\":\"Verify\",\"steps\":[{\"title\":\"Check\",\"instruction\":\"Check it\"}]}");
                return new ModelResponse("{\"title\":\"Streamed\",\"goal\":\"Verify\",\"steps\":[{\"title\":\"Check\",\"instruction\":\"Check it\"}]}",
                        List.of(), "stop");
            }
        };
        AtomicReference<String> output = new AtomicReference<String>("");
        AdaptivePlanService service = new AdaptivePlanService(AgentLoop.compatibility(model, new ToolRegistry(), 2),
                new PlanRegistry(new InMemoryPlanStore()),
                new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8), new ObjectMapper());

        Plan plan = service.create("Verify", null, null, null, false, 4, 1, false,
                new AgentStreamListener() {
                    @Override
                    public void onText(String delta) {
                        output.updateAndGet(value -> value + delta);
                    }

                    @Override
                    public void onToolCall(io.github.git13166956007.dsh.agent.ToolCall call) {
                    }

                    @Override
                    public void onToolResult(io.github.git13166956007.dsh.agent.AgentTraceEvent result) {
                    }
                });

        assertEquals("Streamed", plan.title());
        org.junit.jupiter.api.Assertions.assertTrue(output.get().contains("\"title\":\"Streamed\""));
    }

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
        AdaptivePlanService service = new AdaptivePlanService(AgentLoop.compatibility(model, new ToolRegistry(), 2),
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
        AdaptivePlanService service = new AdaptivePlanService(AgentLoop.compatibility(model, tools, 2),
                new PlanRegistry(new InMemoryPlanStore()), subAgents, new ObjectMapper(), tools, null);

        Plan plan = service.create("Deploy the service", null, null, "model-1", false, 4, 1, true);

        assertEquals("Deployment worker", subAgents.find(plan.steps().get(0).subAgentId()).name());
        assertEquals(List.of(), subAgents.find(plan.steps().get(0).subAgentId()).allowedToolNames());
    }

    @Test
    void persistsDynamicWorkerModelAndExecutionBudgets() throws Exception {
        ChatModel model = (messages, definitions) -> new ModelResponse(
                "{\"title\":\"Analyze\",\"goal\":\"Analyze safely\",\"steps\":[{"
                        + "\"title\":\"Analyze\",\"instruction\":\"Analyze the input\",\"worker\":{"
                        + "\"name\":\"Bounded worker\",\"modelId\":\"model-2\",\"maxTurns\":5,"
                        + "\"maxToolCalls\":7,\"timeoutSeconds\":41,\"maxDepth\":2}}]}",
                List.of(), "stop");
        SubAgentProfileRegistry subAgents = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);
        AdaptivePlanService service = new AdaptivePlanService(AgentLoop.compatibility(model, new ToolRegistry(), 2),
                new PlanRegistry(new InMemoryPlanStore()), subAgents, new ObjectMapper());

        Plan plan = service.create("Analyze the input", null, null, "model-1", false, 4, 1, true);

        var worker = subAgents.find(plan.steps().get(0).subAgentId());
        assertEquals("model-2", worker.modelId());
        assertEquals(5, worker.maxTurns());
        assertEquals(7, worker.maxToolCalls());
        assertEquals(41, worker.timeoutSeconds());
        assertEquals(2, worker.maxDepth());
    }

    @Test
    void preservesDependenciesGeneratedByThePlanningAgent() throws Exception {
        ChatModel model = (messages, definitions) -> new ModelResponse(
                "{\"title\":\"Build task\",\"goal\":\"Build and verify\",\"steps\":["
                        + "{\"title\":\"Build\",\"instruction\":\"Build the project\",\"maxAttempts\":3,\"dependsOn\":[]},"
                        + "{\"title\":\"Verify\",\"instruction\":\"Verify the build\",\"dependsOn\":[1]}]}",
                List.of(), "stop");
        Plan plan = new AdaptivePlanService(AgentLoop.compatibility(model, new ToolRegistry(), 2),
                new PlanRegistry(new InMemoryPlanStore()),
                new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8), new ObjectMapper())
                .create("Build and verify", null, null, null, false, 4, 2);

        assertEquals(List.of(1), plan.steps().get(1).dependsOn());
        assertEquals(3, plan.steps().get(0).maxAttempts());
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
        Plan plan = new AdaptivePlanService(AgentLoop.compatibility(model, new ToolRegistry(), 2),
                new PlanRegistry(new InMemoryPlanStore()), subAgents, new ObjectMapper())
                .create("执行数据库迁移", null, null, null, false, 4, 1);

        assertEquals("数据库迁移 Worker", subAgents.find(plan.steps().get(0).subAgentId()).name());
    }

    @Test
    void ranksCandidatesByPriorityCostAndCurrentLoad() throws Exception {
        SubAgentProfileRegistry subAgents = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);
        var expensive = subAgents.create("Database expert", AgentMode.EXECUTION, null, "database migration", 4,
                List.of(), List.of(), true, 64, 300, 4, 90, 2.0, 2, List.of("database"));
        var cheap = subAgents.create("Database generalist", AgentMode.EXECUTION, null, "database migration", 4,
                List.of(), List.of(), true, 64, 300, 4, 20, 0.1, 1, List.of("database"));
        RunManager runs = new RunManager(new InMemoryRunStore());
        String runId = runs.start(new RunSpec(null, RunKind.SUB_AGENT, null, null, null, cheap.id(), null));
        AdaptivePlanService service = new AdaptivePlanService(AgentLoop.compatibility((messages, definitions) ->
                new ModelResponse("ok", List.of(), "stop"), new ToolRegistry(), 2),
                new PlanRegistry(new InMemoryPlanStore()), subAgents, new ObjectMapper(), null, null, runs, null);

        var candidates = service.rankSubAgents("database migration");

        assertEquals(expensive.id(), candidates.get(0).id());
        assertEquals(1, candidates.stream().filter(candidate -> candidate.id().equals(cheap.id())).findFirst().orElseThrow().activeRuns());
        runs.complete(runId, "done");
    }

    @Test
    void ranksLowerPricedModelsFirstWhenWorkerPoliciesAreEqual() throws Exception {
        ModelRegistry models = new ModelRegistry(new InMemoryModelProfileStore(),
                "https://api.deepseek.com", "deepseek", "deepseek-v4-flash", "", "", 0);
        ObjectMapper mapper = new ObjectMapper();
        models.update("default", mapper.readTree(
                "{\"inputPricePerMillionTokens\":1.0,\"outputPricePerMillionTokens\":1.0}"));
        var cheapModel = models.create("Cheap model", "deepseek", "https://api.deepseek.com",
                "deepseek-v4-flash", "", "", 0, true, false);
        models.update(cheapModel.id(), mapper.readTree(
                "{\"inputPricePerMillionTokens\":0.01,\"outputPricePerMillionTokens\":0.01}"));

        SubAgentProfileRegistry subAgents = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);
        var expensive = subAgents.create("Database worker", AgentMode.EXECUTION, "default",
                "database migration", 4, List.of(), List.of(), true, 64, 300, 4, 50, 1.0, 1,
                List.of("database"));
        var cheap = subAgents.create("Database worker", AgentMode.EXECUTION, cheapModel.id(),
                "database migration", 4, List.of(), List.of(), true, 64, 300, 4, 50, 1.0, 1,
                List.of("database"));
        AdaptivePlanService service = new AdaptivePlanService(AgentLoop.compatibility((messages, definitions) ->
                new ModelResponse("ok", List.of(), "stop"), new ToolRegistry(), 2),
                new PlanRegistry(new InMemoryPlanStore()), subAgents, mapper, null, null, null, models);

        var candidates = service.rankSubAgents("database migration");

        assertEquals(cheap.id(), candidates.get(0).id());
        assertEquals(1.0, candidates.stream().filter(candidate -> candidate.id().equals(expensive.id()))
                .findFirst().orElseThrow().inputPricePerMillionTokens());
        assertEquals(0.01, candidates.stream().filter(candidate -> candidate.id().equals(cheap.id()))
                .findFirst().orElseThrow().outputPricePerMillionTokens());
    }

    @Test
    void demotesWorkersUsingAnUnhealthyModel() throws Exception {
        ModelRegistry models = new ModelRegistry(new InMemoryModelProfileStore(),
                "https://api.deepseek.com", "deepseek", "deepseek-v4-flash", "", "", 0);
        var unhealthyModel = models.create("Unhealthy model", "deepseek", "https://api.deepseek.com",
                "deepseek-v4-flash", "", "", 0, true, false);
        var healthyModel = models.create("Healthy model", "deepseek", "https://api.deepseek.com",
                "deepseek-v4-flash", "", "", 0, true, false);
        models.recordFailure(unhealthyModel.id(), 200, new IllegalStateException("temporary failure"));

        SubAgentProfileRegistry subAgents = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);
        var unhealthy = subAgents.create("Database worker", AgentMode.EXECUTION, unhealthyModel.id(),
                "database migration", 4, List.of(), List.of(), true, 64, 300, 4, 50, 1.0, 1,
                List.of("database"));
        var healthy = subAgents.create("Database worker", AgentMode.EXECUTION, healthyModel.id(),
                "database migration", 4, List.of(), List.of(), true, 64, 300, 4, 50, 1.0, 1,
                List.of("database"));
        AdaptivePlanService service = new AdaptivePlanService(AgentLoop.compatibility((messages, definitions) ->
                new ModelResponse("ok", List.of(), "stop"), new ToolRegistry(), 2),
                new PlanRegistry(new InMemoryPlanStore()), subAgents, new ObjectMapper(), null, null, null, models);

        var candidates = service.rankSubAgents("database migration");

        assertEquals(healthy.id(), candidates.get(0).id());
        var unhealthyCandidate = candidates.stream().filter(candidate -> candidate.id().equals(unhealthy.id()))
                .findFirst().orElseThrow();
        assertEquals("UNHEALTHY", unhealthyCandidate.modelHealthStatus());
        assertEquals(0.0, unhealthyCandidate.modelSuccessRate());
    }

    @Test
    void reportsWorkersWithMissingModelsAsUnavailable() throws Exception {
        ModelRegistry models = new ModelRegistry(new InMemoryModelProfileStore(),
                "https://api.deepseek.com", "deepseek", "deepseek-v4-flash", "", "", 0);
        SubAgentProfileRegistry subAgents = new SubAgentProfileRegistry(new InMemorySubAgentProfileStore(), 8);
        subAgents.create("Broken worker", AgentMode.EXECUTION, "missing-model", "database migration", 4,
                List.of(), List.of(), true, 64, 300, 4, 50, 1.0, 1, List.of("database"));
        AdaptivePlanService service = new AdaptivePlanService(AgentLoop.compatibility((messages, definitions) ->
                new ModelResponse("ok", List.of(), "stop"), new ToolRegistry(), 2),
                new PlanRegistry(new InMemoryPlanStore()), subAgents, new ObjectMapper(), null, null, null, models);

        var candidate = service.rankSubAgents("database migration").get(0);

        assertEquals(false, candidate.available());
        org.junit.jupiter.api.Assertions.assertTrue(candidate.reasons().contains("model unavailable"));
    }
}
