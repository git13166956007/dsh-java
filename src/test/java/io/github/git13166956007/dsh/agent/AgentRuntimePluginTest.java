package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.core.DshRuntime;
import io.github.git13166956007.dsh.plugin.DshServices;
import io.github.git13166956007.dsh.plugin.RuntimeServicePlugin;
import io.github.git13166956007.dsh.tool.ToolRegistry;
import io.github.git13166956007.dsh.core.profile.RuntimeProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class AgentRuntimePluginTest {
    @Test
    void agentResolvesModelAndEventsFromRuntimeScope() throws Exception {
        ChatModel fallback = (messages, tools) -> new ModelResponse("fallback", List.of(), "stop");
        ChatModel runtimeModel = (messages, tools) -> new ModelResponse(messages.get(0).content(), List.of(), "stop");
        DshRuntime runtime = new DshRuntime();
        runtime.install(new RuntimeServicePlugin<>("events", DshServices.EVENTS, runtime.events()));
        runtime.install(new RuntimeServicePlugin<>("model", DshServices.CHAT_MODEL, runtimeModel));
        runtime.install(new RuntimeServicePlugin<>("tools", DshServices.TOOLS, new ToolRegistry()));
        runtime.events().on(AgentEvents.MODEL_REQUEST, (request, next) -> {
            List<ChatMessage> messages = new java.util.ArrayList<>(request.messages());
            messages.set(0, ChatMessage.system("rewritten"));
            return next.proceed(new AgentEvents.ModelRequest(request.runId(), messages, request.tools(),
                    request.apiKey(), request.modelId(), request.streaming()));
        });
        AgentLoop loop = new AgentLoop(runtime.scope(), 2);
        runtime.start();

        assertEquals("rewritten", loop.runDetailed("hello").answer());
        runtime.close();
    }

    @Test
    void runtimeProfileSuppliesPromptAndPermissionDefaults() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> prompt = new java.util.concurrent.atomic.AtomicReference<>();
        ChatModel model = (messages, tools) -> {
            prompt.set(messages.get(0).content());
            return new ModelResponse("done", List.of(), "stop");
        };
        DshRuntime runtime = new DshRuntime();
        runtime.scope().withProfile(new RuntimeProfile("root-agent", null, "profile-model",
                "profile prompt", java.util.Set.of(), java.util.Set.of(),
                java.util.Map.of("tool.*", "deny")));
        runtime.install(new RuntimeServicePlugin<>("model", DshServices.CHAT_MODEL, model));
        runtime.install(new RuntimeServicePlugin<>("tools", DshServices.TOOLS, new ToolRegistry()));
        AgentLoop loop = new AgentLoop(runtime.scope(), 2);
        try {
            assertEquals("done", loop.runDetailed("hello").answer());
            assertTrue(prompt.get().contains("Profile instructions:"));
            assertEquals("profile-model", runtime.scope().profile().modelId());
        } finally {
            runtime.close();
        }
    }
}
