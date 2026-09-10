package io.github.git13166956007.dsh.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentContinuationStore;
import io.github.git13166956007.dsh.agent.AgentProfileRegistry;
import io.github.git13166956007.dsh.agent.AgentProfileStore;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.context.ContextManager;
import io.github.git13166956007.dsh.context.ConversationStore;
import io.github.git13166956007.dsh.context.InMemoryConversationStore;
import io.github.git13166956007.dsh.context.MariaDbConversationStore;
import io.github.git13166956007.dsh.agent.InMemoryAgentProfileStore;
import io.github.git13166956007.dsh.agent.InMemoryAgentContinuationStore;
import io.github.git13166956007.dsh.agent.MariaDbAgentContinuationStore;
import io.github.git13166956007.dsh.agent.MariaDbAgentProfileStore;
import io.github.git13166956007.dsh.agent.InMemorySubAgentProfileStore;
import io.github.git13166956007.dsh.agent.MariaDbSubAgentProfileStore;
import io.github.git13166956007.dsh.agent.SubAgentProfileRegistry;
import io.github.git13166956007.dsh.agent.SubAgentProfileStore;
import io.github.git13166956007.dsh.agent.SubAgentRunner;
import io.github.git13166956007.dsh.core.DshRuntime;
import io.github.git13166956007.dsh.mcp.McpServerRegistry;
import io.github.git13166956007.dsh.mcp.McpClientManager;
import io.github.git13166956007.dsh.mcp.InMemoryMcpServerStore;
import io.github.git13166956007.dsh.mcp.MariaDbMcpServerStore;
import io.github.git13166956007.dsh.mcp.McpServerStore;
import io.github.git13166956007.dsh.memory.InMemoryMemoryStore;
import io.github.git13166956007.dsh.memory.MariaDbMemoryStore;
import io.github.git13166956007.dsh.memory.MemoryManager;
import io.github.git13166956007.dsh.memory.MemoryStore;
import io.github.git13166956007.dsh.run.InMemoryRunStore;
import io.github.git13166956007.dsh.run.MariaDbRunStore;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.run.RunStore;
import io.github.git13166956007.dsh.skill.SkillRegistry;
import io.github.git13166956007.dsh.skill.InMemorySkillStateStore;
import io.github.git13166956007.dsh.skill.MariaDbSkillStateStore;
import io.github.git13166956007.dsh.skill.SkillStateStore;
import io.github.git13166956007.dsh.model.InMemoryModelProfileStore;
import io.github.git13166956007.dsh.model.MariaDbModelProfileStore;
import io.github.git13166956007.dsh.model.ModelProfileStore;
import io.github.git13166956007.dsh.model.ModelRegistry;
import io.github.git13166956007.dsh.model.ModelRouter;
import io.github.git13166956007.dsh.plan.InMemoryPlanStore;
import io.github.git13166956007.dsh.plan.MariaDbPlanStore;
import io.github.git13166956007.dsh.plan.PlanExecutor;
import io.github.git13166956007.dsh.plan.AdaptivePlanService;
import io.github.git13166956007.dsh.plan.PlanRegistry;
import io.github.git13166956007.dsh.plan.PlanStore;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.tool.InMemoryToolProfileStore;
import io.github.git13166956007.dsh.tool.MariaDbToolProfileStore;
import io.github.git13166956007.dsh.tool.ToolProfileStore;
import java.time.OffsetDateTime;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DshRuntimeConfiguration {
    @Bean(destroyMethod = "close")
    public DshRuntime dshRuntime() {
        DshRuntime runtime = new DshRuntime();
        runtime.start();
        return runtime;
    }

    @Bean
    public ToolProfileStore toolProfileStore(Environment environment, ObjectMapper objectMapper) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryToolProfileStore();
        return new MariaDbToolProfileStore(environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"), environment.getProperty("dsh.persistence.password"), objectMapper);
    }

    @Bean
    public ToolRegistry toolRegistry(ObjectMapper objectMapper, ToolProfileStore profiles) {
        ToolRegistry registry = new ToolRegistry(profiles);
        ObjectNode noArguments = objectMapper.createObjectNode();
        noArguments.put("type", "object");
        registry.register(new ToolDefinition(
                "time_now",
                "Get the current server time in ISO-8601 format.",
                noArguments), arguments -> OffsetDateTime.now().toString());
        return registry;
    }

    @Bean
    public ModelProfileStore modelProfileStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryModelProfileStore();
        return new MariaDbModelProfileStore(
                environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"),
                environment.getProperty("dsh.persistence.password"),
                environment.getProperty("dsh.security.secret-key", environment.getProperty("DSH_SECRET_KEY", "")));
    }

    @Bean
    public ModelRegistry modelRegistry(ModelProfileStore store, Environment environment) {
        return new ModelRegistry(store,
                environment.getProperty("dsh.model.base-url", "https://api.deepseek.com"),
                environment.getProperty("dsh.model.provider", "deepseek"),
                environment.getProperty("dsh.model.name", "deepseek-v4-flash"),
                environment.getProperty("dsh.model.api-key", environment.getProperty("DEEPSEEK_API_KEY", "")),
                environment.getProperty("dsh.model.proxy-host", environment.getProperty("DEEPSEEK_PROXY_HOST", "")),
                Integer.parseInt(environment.getProperty("dsh.model.proxy-port", environment.getProperty("DEEPSEEK_PROXY_PORT", "0"))));
    }

    @Bean
    public AgentProfileStore agentProfileStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryAgentProfileStore();
        return new MariaDbAgentProfileStore(
                environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"),
                environment.getProperty("dsh.persistence.password"));
    }

    @Bean
    public AgentProfileRegistry agentProfileRegistry(AgentProfileStore store, Environment environment) {
        return new AgentProfileRegistry(store, Integer.parseInt(
                environment.getProperty("dsh.agent.max-turns", "8")));
    }

    @Bean
    public AgentContinuationStore agentContinuationStore(Environment environment, RunStore runStore) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryAgentContinuationStore();
        return new MariaDbAgentContinuationStore(
                environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"),
                environment.getProperty("dsh.persistence.password"));
    }

    @Bean
    public SubAgentProfileStore subAgentProfileStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemorySubAgentProfileStore();
        return new MariaDbSubAgentProfileStore(
                environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"),
                environment.getProperty("dsh.persistence.password"));
    }

    @Bean
    public SubAgentProfileRegistry subAgentProfileRegistry(SubAgentProfileStore store, Environment environment) {
        return new SubAgentProfileRegistry(store, Integer.parseInt(
                environment.getProperty("dsh.agent.max-turns", "8")));
    }

    @Bean
    public MemoryStore memoryStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryMemoryStore();
        return new MariaDbMemoryStore(
                environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"),
                environment.getProperty("dsh.persistence.password"));
    }

    @Bean
    public MemoryManager memoryManager(MemoryStore store) {
        return new MemoryManager(store);
    }

    @Bean
    public RunStore runStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryRunStore();
        return new MariaDbRunStore(
                environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"),
                environment.getProperty("dsh.persistence.password"));
    }

    @Bean
    public RunManager runManager(RunStore store) {
        return new RunManager(store);
    }

    @Bean
    public ChatModel chatModel(ObjectMapper objectMapper, ModelRegistry modelRegistry) {
        return new ModelRouter(modelRegistry, objectMapper);
    }

    @Bean
    public SkillStateStore skillStateStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemorySkillStateStore();
        return new MariaDbSkillStateStore(environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"), environment.getProperty("dsh.persistence.password"));
    }

    @Bean
    public SkillRegistry skillRegistry(Environment environment, SkillStateStore stateStore) {
        return new SkillRegistry(environment.getProperty("dsh.skills.directory",
                environment.getProperty("DSH_SKILLS_DIR", "skills")), stateStore);
    }

    @Bean
    public AgentLoop agentLoop(ChatModel chatModel, ToolRegistry toolRegistry, SkillRegistry skillRegistry,
                               AgentProfileRegistry agentProfileRegistry, MemoryManager memoryManager,
                               RunManager runManager, AgentContinuationStore continuations,
                               ObjectMapper objectMapper, Environment environment) {
        return new AgentLoop(chatModel, toolRegistry, skillRegistry, agentProfileRegistry, memoryManager, runManager,
                continuations, objectMapper, Integer.parseInt(environment.getProperty("dsh.agent.max-turns", "8")));
    }

    @Bean
    public SubAgentRunner subAgentRunner(AgentLoop agentLoop, SubAgentProfileRegistry profiles) {
        return new SubAgentRunner(agentLoop, profiles);
    }

    @Bean
    public PlanStore planStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryPlanStore();
        return new MariaDbPlanStore(
                environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"),
                environment.getProperty("dsh.persistence.password"));
    }

    @Bean
    public PlanRegistry planRegistry(PlanStore store) {
        return new PlanRegistry(store);
    }

    @Bean
    public AdaptivePlanService adaptivePlanService(AgentLoop agentLoop, PlanRegistry planRegistry,
                                                   SubAgentProfileRegistry subAgentProfileRegistry,
                                                   ObjectMapper objectMapper, ToolRegistry toolRegistry,
                                                   SkillRegistry skillRegistry) {
        return new AdaptivePlanService(agentLoop, planRegistry, subAgentProfileRegistry, objectMapper,
                toolRegistry, skillRegistry);
    }

    @Bean(destroyMethod = "close")
    public PlanExecutor planExecutor(PlanRegistry planRegistry, AgentLoop agentLoop, SubAgentRunner subAgents,
                                     RunManager runManager) {
        return new PlanExecutor(planRegistry, agentLoop, subAgents, runManager);
    }

    @Bean
    public ConversationStore conversationStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryConversationStore();
        return new MariaDbConversationStore(
                environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"),
                environment.getProperty("dsh.persistence.password"));
    }

    @Bean
    public ContextManager contextManager(ConversationStore conversationStore, Environment environment) {
        return new ContextManager(conversationStore, Integer.parseInt(
                environment.getProperty("dsh.persistence.max-history-messages", "24")), Integer.parseInt(
                environment.getProperty("dsh.persistence.max-context-tokens", "12000")));
    }

    @Bean
    public McpServerStore mcpServerStore(Environment environment, ObjectMapper objectMapper) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryMcpServerStore();
        return new MariaDbMcpServerStore(environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"), environment.getProperty("dsh.persistence.password"),
                objectMapper, environment.getProperty("dsh.security.secret-key", environment.getProperty("DSH_SECRET_KEY", "")));
    }

    @Bean
    public McpServerRegistry mcpServerRegistry(McpServerStore store) {
        return new McpServerRegistry(store);
    }

    @Bean(destroyMethod = "close")
    public McpClientManager mcpClientManager(McpServerRegistry mcpServerRegistry, ToolRegistry toolRegistry,
                                             ObjectMapper objectMapper) {
        McpClientManager manager = new McpClientManager(mcpServerRegistry, toolRegistry, objectMapper);
        manager.restoreEnabled();
        return manager;
    }
}
