package io.github.git13166956007.dsh.model;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.agent.ModelStreamListener;
import io.github.git13166956007.dsh.provider.deepseek.DeepSeekChatModel;
import io.github.git13166956007.dsh.provider.deepseek.ModelConfigurationException;
import io.github.git13166956007.dsh.provider.deepseek.ModelQuotaException;
import io.github.git13166956007.dsh.provider.openai.OpenAiCompatibleChatModel;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
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
        registerBuiltInProviders();
    }

    @Override
    public ModelTokenizer tokenizer(String modelId) {
        return registry.tokenizer(modelId);
    }

    @Override
    public int contextWindow(String modelId) {
        return registry.contextWindow(modelId);
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
                registry.recordUsage(profile.id(), messages, response);
                return response;
            } catch (Exception exception) {
                registry.recordFailure(profile.id(), elapsedMs(started), exception);
                if (!shouldFailover(profile, exception)) throw exception;
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
                registry.recordUsage(profile.id(), messages, response);
                return response;
            } catch (Exception exception) {
                registry.recordFailure(profile.id(), elapsedMs(started), exception);
                if (!shouldFailover(profile, exception)) throw exception;
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

    private static boolean shouldFailover(ModelProfileData profile, Exception exception) {
        ModelFailoverPolicy policy = ModelFailoverPolicy.parse(profile.failoverPolicy());
        return switch (policy) {
            case ANY_FAILURE -> true;
            case DISABLED -> false;
            case TRANSIENT_FAILURE -> isTransient(exception);
        };
    }

    private static boolean isTransient(Exception exception) {
        if (exception instanceof ModelQuotaException || exception instanceof ModelConfigurationException) return false;
        if (exception instanceof IOException || exception instanceof HttpTimeoutException) return true;
        String message = exception.getMessage();
        if (message == null) return false;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("API returned (408|409|425|429|500|502|503|504):")
                .matcher(message);
        return matcher.find();
    }

    private ChatModel client(ModelProfileData profile) {
        ModelProvider provider = registry.provider(profile.provider());
        if (provider == null) {
            throw new IllegalArgumentException("unsupported model provider: " + profile.provider());
        }
        return provider.create(profile, objectMapper);
    }

    private void registerBuiltInProviders() {
        registry.registerProvider(new ModelProvider() {
            @Override
            public String id() {
                return "deepseek";
            }

            @Override
            public ChatModel create(ModelProfileData profile, ObjectMapper mapper) {
                return new DeepSeekChatModel(mapper, profile.baseUrl(), profile.apiKey(), profile.model(),
                        profile.proxyHost(), profile.proxyPort(), profile.temperature(), profile.topP(),
                        profile.maxTokens(), profile.frequencyPenalty(), profile.presencePenalty(),
                        profile.timeoutSeconds(), profile.requestOptionsJson());
            }
        });
        registry.registerProvider(new ModelProvider() {
            @Override
            public String id() {
                return "openai";
            }

            @Override
            public ChatModel create(ModelProfileData profile, ObjectMapper mapper) {
                return openAi(profile, mapper);
            }
        });
        registry.registerProvider(new ModelProvider() {
            @Override
            public String id() {
                return "openai_compatible";
            }

            @Override
            public ChatModel create(ModelProfileData profile, ObjectMapper mapper) {
                return openAi(profile, mapper);
            }
        });
    }

    private static ChatModel openAi(ModelProfileData profile, ObjectMapper mapper) {
        return new OpenAiCompatibleChatModel(mapper, profile.provider(), profile.baseUrl(), profile.apiKey(),
                profile.model(), profile.proxyHost(), profile.proxyPort(), profile.temperature(), profile.topP(),
                profile.maxTokens(), profile.frequencyPenalty(), profile.presencePenalty(), profile.timeoutSeconds(),
                profile.requestOptionsJson());
    }
}
