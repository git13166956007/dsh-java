package io.github.git13166956007.dsh.model;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.agent.ModelStreamListener;
import io.github.git13166956007.dsh.provider.deepseek.DeepSeekChatModel;
import io.github.git13166956007.dsh.provider.openai.OpenAiCompatibleChatModel;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModelRouter implements ChatModel {
    private final ModelRegistry registry;
    private final ObjectMapper objectMapper;

    public ModelRouter(ModelRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws Exception {
        return complete(messages, tools, null, null);
    }

    @Override
    public ModelResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools,
                                  String apiKey, String modelId) throws Exception {
        Exception lastFailure = null;
        for (ModelProfileData profile : registry.resolveCandidates(modelId)) {
            long started = System.nanoTime();
            try {
                ModelResponse response = client(profile).complete(messages, effectiveTools(profile, tools), apiKey);
                registry.recordSuccess(profile.id(), elapsedMs(started));
                return response;
            } catch (Exception exception) {
                registry.recordFailure(profile.id(), elapsedMs(started), exception);
                if (lastFailure != null) exception.addSuppressed(lastFailure);
                lastFailure = exception;
            }
        }
        throw lastFailure == null ? new IllegalStateException("no model candidates available") : lastFailure;
    }

    @Override
    public ModelResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                String apiKey, String modelId, ModelStreamListener listener) throws Exception {
        Exception lastFailure = null;
        AtomicBoolean emitted = new AtomicBoolean(false);
        for (ModelProfileData profile : registry.resolveCandidates(modelId)) {
            List<ToolDefinition> effectiveTools = effectiveTools(profile, tools);
            long started = System.nanoTime();
            try {
                ModelStreamListener guardedListener = delta -> {
                    if (delta != null && !delta.isEmpty()) emitted.set(true);
                    listener.onText(delta);
                };
                ModelResponse response;
                if (!profile.supportsStreaming()) {
                    response = client(profile).complete(messages, effectiveTools, apiKey);
                    if (response.content() != null && !response.content().isEmpty()) guardedListener.onText(response.content());
                } else {
                    response = client(profile).stream(messages, effectiveTools, apiKey, guardedListener);
                }
                registry.recordSuccess(profile.id(), elapsedMs(started));
                return response;
            } catch (Exception exception) {
                registry.recordFailure(profile.id(), elapsedMs(started), exception);
                if (emitted.get()) throw exception;
                if (lastFailure != null) exception.addSuppressed(lastFailure);
                lastFailure = exception;
            }
        }
        throw lastFailure == null ? new IllegalStateException("no model candidates available") : lastFailure;
    }

    private static List<ToolDefinition> effectiveTools(ModelProfileData profile, List<ToolDefinition> tools) {
        return profile.supportsTools() ? tools : List.of();
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private ChatModel client(ModelProfileData profile) {
        String provider = profile.provider().toLowerCase(Locale.ROOT);
        if ("deepseek".equals(provider)) {
            return new DeepSeekChatModel(objectMapper, profile.baseUrl(), profile.apiKey(), profile.model(),
                    profile.proxyHost(), profile.proxyPort(), profile.temperature(), profile.topP(), profile.maxTokens(),
                    profile.frequencyPenalty(), profile.presencePenalty(), profile.timeoutSeconds(), profile.requestOptionsJson());
        }
        if ("openai".equals(provider) || "openai_compatible".equals(provider)) {
            return new OpenAiCompatibleChatModel(objectMapper, provider, profile.baseUrl(), profile.apiKey(),
                    profile.model(), profile.proxyHost(), profile.proxyPort(), profile.temperature(), profile.topP(),
                    profile.maxTokens(), profile.frequencyPenalty(), profile.presencePenalty(), profile.timeoutSeconds(),
                    profile.requestOptionsJson());
        }
        throw new IllegalArgumentException("unsupported model provider: " + profile.provider()
                + "; use deepseek, openai, or openai_compatible");
    }
}
