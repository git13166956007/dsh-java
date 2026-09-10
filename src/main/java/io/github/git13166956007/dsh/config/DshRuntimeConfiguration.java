package io.github.git13166956007.dsh.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.context.ContextManager;
import io.github.git13166956007.dsh.context.ConversationStore;
import io.github.git13166956007.dsh.context.InMemoryConversationStore;
import io.github.git13166956007.dsh.context.MariaDbConversationStore;
import io.github.git13166956007.dsh.core.DshRuntime;
import io.github.git13166956007.dsh.mcp.McpServerRegistry;
import io.github.git13166956007.dsh.mcp.McpClientManager;
import io.github.git13166956007.dsh.skill.SkillRegistry;
import io.github.git13166956007.dsh.provider.deepseek.DeepSeekChatModel;
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
    public ChatModel chatModel(ObjectMapper objectMapper, Environment environment) {
        return new DeepSeekChatModel(
                objectMapper,
                environment.getProperty("dsh.model.base-url", "https://api.deepseek.com"),
                environment.getProperty("dsh.model.api-key",
                        environment.getProperty("DEEPSEEK_API_KEY", "")),
                environment.getProperty("dsh.model.name",
                        environment.getProperty("DEEPSEEK_MODEL", "deepseek-v4-flash")),
                environment.getProperty("dsh.model.proxy-host",
                        environment.getProperty("DEEPSEEK_PROXY_HOST", "")),
                Integer.parseInt(environment.getProperty("dsh.model.proxy-port",
                        environment.getProperty("DEEPSEEK_PROXY_PORT", "0"))));
    }

    @Bean
    public SkillRegistry skillRegistry(Environment environment) {
        return new SkillRegistry(environment.getProperty("dsh.skills.directory",
                environment.getProperty("DSH_SKILLS_DIR", "skills")));
    }

    @Bean
    public AgentLoop agentLoop(ChatModel chatModel, ToolRegistry toolRegistry, SkillRegistry skillRegistry) {
        return new AgentLoop(chatModel, toolRegistry, skillRegistry, 8);
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
