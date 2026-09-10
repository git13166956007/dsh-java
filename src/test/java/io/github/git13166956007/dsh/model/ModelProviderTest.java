package io.github.git13166956007.dsh.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ModelProviderTest {
    @Test
    void routesThroughAProviderRegisteredByAPlugin() throws Exception {
        ModelRegistry registry = new ModelRegistry(new InMemoryModelProfileStore(),
                "https://api.deepseek.com", "deepseek", "fallback", "key", "", 0);
        registry.registerProvider(new ModelProvider() {
            @Override
            public String id() {
                return "fixture";
            }

            @Override
            public ChatModel create(ModelProfileData profile, ObjectMapper objectMapper) {
                return new ChatModel() {
                    @Override
                    public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                        return new ModelResponse("provider=" + profile.model(), List.of(), "stop");
                    }
                };
            }
        });
        ModelProfile profile = registry.create("Fixture", "fixture", "https://example.com", "fixture-model",
                "", "", 0, true, false);

        ModelResponse response = new ModelRouter(registry, new ObjectMapper()).complete(
                List.of(ChatMessage.user("ping")), List.of(), null, profile.id());

        assertEquals("provider=fixture-model", response.content());
    }

    @Test
    void rejectsDuplicateProviderIds() {
        ModelRegistry registry = new ModelRegistry(new InMemoryModelProfileStore(),
                "https://api.deepseek.com", "deepseek", "fallback", "key", "", 0);
        ModelProvider provider = new ModelProvider() {
            @Override
            public String id() {
                return "fixture";
            }

            @Override
            public ChatModel create(ModelProfileData profile, ObjectMapper objectMapper) {
                return null;
            }
        };
        registry.registerProvider(provider);
        assertThrows(IllegalArgumentException.class, () -> registry.registerProvider(new ModelProvider() {
            @Override
            public String id() {
                return "fixture";
            }

            @Override
            public ChatModel create(ModelProfileData profile, ObjectMapper objectMapper) {
                return null;
            }
        }));
    }
}
