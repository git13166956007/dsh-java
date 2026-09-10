package io.github.git13166956007.dsh.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.AgentProfileRegistry;
import io.github.git13166956007.dsh.agent.AgentProfileStore;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.context.ContextManager;
import io.github.git13166956007.dsh.context.ConversationStore;
import io.github.git13166956007.dsh.context.InMemoryConversationStore;
import io.github.git13166956007.dsh.context.MariaDbConversationStore;
import io.github.git13166956007.dsh.agent.InMemoryAgentProfileStore;
import io.github.git13166956007.dsh.agent.MariaDbAgentProfileStore;
import io.github.git13166956007.dsh.agent.InMemorySubAgentProfileStore;
import io.github.git13166956007.dsh.agent.MariaDbSubAgentProfileStore;
import io.github.git13166956007.dsh.agent.SubAgentProfileRegistry;
import io.github.git13166956007.dsh.agent.SubAgentProfileStore;
import io.github.git13166956007.dsh.agent.SubAgentRunner;
import io.github.git13166956007.dsh.core.DshRuntime;
import io.github.git13166956007.dsh.mcp.McpServerRegistry;
import io.github.git13166956007.dsh.mcp.McpClientManager;
import io.github.git13166956007.dsh.skill.SkillRegistry;
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
    public ToolRegistry toolRegistry(ObjectMapper objectMapper) {
        ToolRegistry registry = new ToolRegistry();
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
                environment.getProperty("dsh.persistence.password"));
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
    public ChatModel chatModel(ObjectMapper objectMapper, ModelRegistry modelRegistry) {
        return new ModelRouter(modelRegistry, objectMapper);
    }

    @Bean
    public SkillRegistry skillRegistry(Environment environment) {
        return new SkillRegistry(environment.getProperty("dsh.skills.directory",
                environment.getProperty("DSH_SKILLS_DIR", "skills")));
    }

    @Bean
    public AgentLoop agentLoop(ChatModel chatModel, ToolRegistry toolRegistry, SkillRegistry skillRegistry,
                               AgentProfileRegistry agentProfileRegistry, Environment environment) {
        return new AgentLoop(chatModel, toolRegistry, skillRegistry, agentProfileRegistry,
                Integer.parseInt(environment.getProperty("dsh.agent.max-turns", "8")));
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
                                                   ObjectMapper objectMapper) {
        return new AdaptivePlanService(agentLoop, planRegistry, subAgentProfileRegistry, objectMapper);
    }

    @Bean(destroyMethod = "close")
    public PlanExecutor planExecutor(PlanRegistry planRegistry, AgentLoop agentLoop, SubAgentRunner subAgents) {
        return new PlanExecutor(planRegistry, agentLoop, subAgents);
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
                environment.getProperty("dsh.persistence.max-history-messages", "24")));
    }

    @Bean
    public McpServerRegistry mcpServerRegistry() {
        return new McpServerRegistry();
    }

    @Bean(destroyMethod = "close")
    public McpClientManager mcpClientManager(McpServerRegistry mcpServerRegistry, ToolRegistry toolRegistry,
                                             ObjectMapper objectMapper) {
        return new McpClientManager(mcpServerRegistry, toolRegistry, objectMapper);
    }
}
