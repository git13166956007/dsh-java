package io.github.git13166956007.dsh.plugin;

import io.github.git13166956007.dsh.agent.AgentProfileRegistry;
import io.github.git13166956007.dsh.agent.SubAgentProfileRegistry;
import io.github.git13166956007.dsh.context.ContextManager;
import io.github.git13166956007.dsh.context.ConversationStore;
import io.github.git13166956007.dsh.memory.MemoryManager;
import io.github.git13166956007.dsh.mcp.McpServerRegistry;
import io.github.git13166956007.dsh.model.ModelRegistry;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.skill.SkillRegistry;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.agent.AgentLoop;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.event.EventBus;
import io.github.git13166956007.dsh.workspace.WorkspaceRegistry;
import io.github.git13166956007.dsh.service.ServiceKey;

public final class DshServices {
    public static final ServiceKey<ToolRegistry> TOOLS = new ServiceKey<ToolRegistry>("tools", ToolRegistry.class);
    public static final ServiceKey<ModelRegistry> MODELS = new ServiceKey<ModelRegistry>("models", ModelRegistry.class);
    public static final ServiceKey<McpServerRegistry> MCP_SERVERS = new ServiceKey<McpServerRegistry>("mcp.servers", McpServerRegistry.class);
    public static final ServiceKey<SkillRegistry> SKILLS = new ServiceKey<SkillRegistry>("skills", SkillRegistry.class);
    public static final ServiceKey<AgentProfileRegistry> AGENTS = new ServiceKey<AgentProfileRegistry>("agents", AgentProfileRegistry.class);
    public static final ServiceKey<SubAgentProfileRegistry> SUB_AGENTS = new ServiceKey<SubAgentProfileRegistry>("sub-agents", SubAgentProfileRegistry.class);
    public static final ServiceKey<MemoryManager> MEMORIES = new ServiceKey<MemoryManager>("memories", MemoryManager.class);
    public static final ServiceKey<ContextManager> CONTEXT = new ServiceKey<ContextManager>("context", ContextManager.class);
    public static final ServiceKey<RunManager> RUNS = new ServiceKey<RunManager>("runs", RunManager.class);
    public static final ServiceKey<WorkspaceRegistry> WORKSPACES = new ServiceKey<WorkspaceRegistry>("workspaces", WorkspaceRegistry.class);
    public static final ServiceKey<ConversationStore> CONVERSATIONS = new ServiceKey<ConversationStore>("conversations", ConversationStore.class);
    public static final ServiceKey<AgentLoop> AGENT_LOOP = new ServiceKey<AgentLoop>("agent.loop", AgentLoop.class);
    public static final ServiceKey<ChatModel> CHAT_MODEL = new ServiceKey<ChatModel>("chat.model", ChatModel.class);
    public static final ServiceKey<EventBus> EVENTS = new ServiceKey<EventBus>("events", EventBus.class);

    private DshServices() {
    }
}
