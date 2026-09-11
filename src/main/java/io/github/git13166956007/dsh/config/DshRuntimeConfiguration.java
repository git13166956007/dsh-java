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
import io.github.git13166956007.dsh.agent.SubAgentSessionStore;
import io.github.git13166956007.dsh.agent.InMemorySubAgentSessionStore;
import io.github.git13166956007.dsh.agent.MariaDbSubAgentSessionStore;
import io.github.git13166956007.dsh.agent.SubAgentSessionManager;
import io.github.git13166956007.dsh.agent.AgentRunRecovery;
import io.github.git13166956007.dsh.core.DshRuntime;
import io.github.git13166956007.dsh.mcp.McpServerRegistry;
import io.github.git13166956007.dsh.mcp.McpClientManager;
import io.github.git13166956007.dsh.mcp.InMemoryMcpServerStore;
import io.github.git13166956007.dsh.mcp.MariaDbMcpServerStore;
import io.github.git13166956007.dsh.mcp.InMemoryMcpHealthStore;
import io.github.git13166956007.dsh.mcp.MariaDbMcpHealthStore;
import io.github.git13166956007.dsh.mcp.McpHealthStore;
import io.github.git13166956007.dsh.mcp.McpServerStore;
import io.github.git13166956007.dsh.mcp.InMemoryMcpResourceSubscriptionStore;
import io.github.git13166956007.dsh.mcp.MariaDbMcpResourceSubscriptionStore;
import io.github.git13166956007.dsh.mcp.McpResourceSubscriptionStore;
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
import io.github.git13166956007.dsh.model.MariaDbModelHealthStore;
import io.github.git13166956007.dsh.model.InMemoryModelHealthStore;
import io.github.git13166956007.dsh.model.MariaDbModelUsageStore;
import io.github.git13166956007.dsh.model.InMemoryModelUsageStore;
import io.github.git13166956007.dsh.model.ModelHealthStore;
import io.github.git13166956007.dsh.model.ModelUsageStore;
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
import io.github.git13166956007.dsh.plugin.DshServices;
import io.github.git13166956007.dsh.core.scope.Scope;
import io.github.git13166956007.dsh.event.MariaDbEventJournal;
import io.github.git13166956007.dsh.tool.WorkspaceToolProvider;
import io.github.git13166956007.dsh.tool.WorkspaceProcessToolProvider;
import io.github.git13166956007.dsh.security.PermissionPolicyEngine;
import io.github.git13166956007.dsh.workspace.InMemoryWorkspaceStore;
import io.github.git13166956007.dsh.workspace.MariaDbWorkspaceStore;
import io.github.git13166956007.dsh.workspace.WorkspaceRegistry;
import io.github.git13166956007.dsh.workspace.WorkspaceStore;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DshRuntimeConfiguration {
    @Bean(destroyMethod = "close")
    public DshRuntime dshRuntime(ToolRegistry toolRegistry, ModelRegistry modelRegistry,
                                McpServerRegistry mcpServerRegistry, SkillRegistry skillRegistry,
                                AgentProfileRegistry agentProfileRegistry,
                                SubAgentProfileRegistry subAgentProfileRegistry, MemoryManager memoryManager,
                                ContextManager contextManager, RunManager runManager, WorkspaceRegistry workspaces,
                                ConversationStore conversationStore, AgentLoop agentLoop, ChatModel chatModel,
                                AgentContinuationStore continuations, Scope runtimeScope, Environment environment) {
        boolean persistenceEnabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        DshRuntime runtime = new DshRuntime(runtimeScope, persistenceEnabled
                ? new MariaDbEventJournal(environment.getProperty("dsh.persistence.jdbc-url"),
                        environment.getProperty("dsh.persistence.username"),
                        environment.getProperty("dsh.persistence.password"), new ObjectMapper())
                : null);
        try {
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.events", DshServices.EVENTS, runtime.events()));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.policy", DshServices.POLICY, new PermissionPolicyEngine()));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.conversations", DshServices.CONVERSATIONS, conversationStore));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.tools", DshServices.TOOLS, toolRegistry));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.models", DshServices.MODELS, modelRegistry));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.chat-model", DshServices.CHAT_MODEL, chatModel, "core.models"));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.mcp", DshServices.MCP_SERVERS, mcpServerRegistry, "core.tools"));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.skills", DshServices.SKILLS, skillRegistry));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.agents", DshServices.AGENTS, agentProfileRegistry));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.sub-agents", DshServices.SUB_AGENTS, subAgentProfileRegistry, "core.agents"));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.memories", DshServices.MEMORIES, memoryManager));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.context", DshServices.CONTEXT, contextManager, "core.conversations"));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.runs", DshServices.RUNS, runManager));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.continuations", DshServices.CONTINUATIONS, continuations));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.workspaces", DshServices.WORKSPACES, workspaces));
            runtime.install(new io.github.git13166956007.dsh.plugin.RuntimeServicePlugin<>(
                    "core.agent-loop", DshServices.AGENT_LOOP, agentLoop,
                    "core.tools", "core.models", "core.context", "core.runs"));
            runtime.loadPlugins(Path.of(environment.getProperty("dsh.plugins.directory", "plugins")));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load DSH plugins", exception);
        }
        runtime.start();
        return runtime;
    }

    @Bean
    public Scope runtimeScope() {
        return new Scope("runtime");
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
    public WorkspaceToolProvider workspaceToolProvider(ToolRegistry tools, ObjectMapper objectMapper,
                                                       WorkspaceRegistry workspaces,
                                                       Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.tools.workspace.enabled", "false"));
        WorkspaceToolProvider provider = new WorkspaceToolProvider(workspaces, objectMapper);
        if (enabled) provider.register(tools);
        return provider;
    }

    @Bean(destroyMethod = "close")
    public WorkspaceProcessToolProvider workspaceProcessToolProvider(ToolRegistry tools, ObjectMapper objectMapper,
                                                                      WorkspaceRegistry workspaces,
                                                                      Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.tools.process.enabled", "false"));
        WorkspaceProcessToolProvider provider = new WorkspaceProcessToolProvider(workspaces, objectMapper);
        if (enabled) provider.register(tools);
        return provider;
    }

    @Bean
    public WorkspaceStore workspaceStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryWorkspaceStore();
        return new MariaDbWorkspaceStore(environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"), environment.getProperty("dsh.persistence.password"));
    }

    @Bean
    public WorkspaceRegistry workspaceRegistry(WorkspaceStore store, Environment environment) {
        Set<String> commands = Arrays.stream(environment.getProperty("dsh.tools.process.allowed-commands", "").split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toSet());
        return new WorkspaceRegistry(store, environment.getProperty("dsh.tools.workspace.directory", "."),
                Long.parseLong(environment.getProperty("dsh.tools.workspace.max-read-bytes", "1000000")),
                Long.parseLong(environment.getProperty("dsh.tools.workspace.max-write-bytes", "1000000")),
                Boolean.parseBoolean(environment.getProperty("dsh.tools.workspace.write-enabled", "false")),
                Integer.parseInt(environment.getProperty("dsh.tools.process.max-timeout-seconds", "120")),
                Long.parseLong(environment.getProperty("dsh.tools.process.max-output-bytes", "1000000")), commands);
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
    public ModelHealthStore modelHealthStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryModelHealthStore();
        return new MariaDbModelHealthStore(
                environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"),
                environment.getProperty("dsh.persistence.password"));
    }

    @Bean
    public ModelUsageStore modelUsageStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryModelUsageStore();
        return new MariaDbModelUsageStore(
                environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"),
                environment.getProperty("dsh.persistence.password"));
    }

    @Bean
    public ModelRegistry modelRegistry(ModelProfileStore store, ModelHealthStore healthStore,
                                       ModelUsageStore usageStore, Environment environment) {
        return new ModelRegistry(store, healthStore, usageStore,
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
    public SubAgentSessionStore subAgentSessionStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemorySubAgentSessionStore();
        return new MariaDbSubAgentSessionStore(environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"), environment.getProperty("dsh.persistence.password"));
    }

    @Bean
    public SubAgentSessionManager subAgentSessionManager(SubAgentSessionStore store, ContextManager contextManager,
                                                         SubAgentRunner subAgentRunner,
                                                         SubAgentProfileRegistry profiles, ModelRegistry models,
                                                         RunManager runs) {
        return new SubAgentSessionManager(store, contextManager, subAgentRunner, profiles, models, runs);
    }

    @Bean(initMethod = "recover")
    public AgentRunRecovery agentRunRecovery(AgentContinuationStore continuations, RunManager runs,
                                             AgentLoop agentLoop,
                                             SubAgentProfileRegistry profiles, SubAgentSessionManager sessions,
                                             SubAgentRunner subAgents) {
        return new AgentRunRecovery(continuations, runs, agentLoop, profiles, sessions, subAgents);
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
    public MemoryManager memoryManager(MemoryStore store, ObjectMapper objectMapper) {
        return new MemoryManager(store, objectMapper);
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

    @Bean(destroyMethod = "close")
    public AgentLoop agentLoop(Scope runtimeScope, Environment environment) {
        return new AgentLoop(runtimeScope, Integer.parseInt(environment.getProperty("dsh.agent.max-turns", "8")));
    }

    @Bean
    public SubAgentRunner subAgentRunner(AgentLoop agentLoop, SubAgentProfileRegistry profiles, RunManager runs) {
        SubAgentRunner runner = new SubAgentRunner(agentLoop, profiles, runs);
        agentLoop.setSubAgentRunner(runner);
        return runner;
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
                                                   SkillRegistry skillRegistry, RunManager runManager,
                                                   ModelRegistry modelRegistry) {
        return new AdaptivePlanService(agentLoop, planRegistry, subAgentProfileRegistry, objectMapper,
                toolRegistry, skillRegistry, runManager, modelRegistry);
    }

    @Bean(initMethod = "recover", destroyMethod = "close")
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
                environment.getProperty("dsh.persistence.max-context-tokens", "12000")), Integer.parseInt(
                environment.getProperty("dsh.context.max-provider-tokens", "4000")));
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

    @Bean
    public McpHealthStore mcpHealthStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryMcpHealthStore();
        return new MariaDbMcpHealthStore(environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"), environment.getProperty("dsh.persistence.password"));
    }

    @Bean
    public McpResourceSubscriptionStore mcpResourceSubscriptionStore(Environment environment) {
        boolean enabled = Boolean.parseBoolean(environment.getProperty("dsh.persistence.enabled", "false"));
        if (!enabled) return new InMemoryMcpResourceSubscriptionStore();
        return new MariaDbMcpResourceSubscriptionStore(environment.getProperty("dsh.persistence.jdbc-url"),
                environment.getProperty("dsh.persistence.username"), environment.getProperty("dsh.persistence.password"));
    }

    @Bean(destroyMethod = "close")
    public McpClientManager mcpClientManager(McpServerRegistry mcpServerRegistry, ToolRegistry toolRegistry,
                                             ObjectMapper objectMapper, McpHealthStore mcpHealthStore,
                                             McpResourceSubscriptionStore mcpResourceSubscriptionStore,
                                             Environment environment) {
        McpClientManager manager = new McpClientManager(mcpServerRegistry, toolRegistry, objectMapper, mcpHealthStore,
                mcpResourceSubscriptionStore,
                Long.parseLong(environment.getProperty("dsh.mcp.reconnect.initial-delay-ms", "1000")),
                Long.parseLong(environment.getProperty("dsh.mcp.reconnect.max-delay-ms", "60000")),
                Integer.parseInt(environment.getProperty("dsh.mcp.reconnect.max-attempts", "8")));
        manager.restoreEnabled();
        return manager;
    }
}
