package io.github.git13166956007.dsh.model;

import io.github.git13166956007.dsh.agent.ChatModel;
import tools.jackson.databind.ObjectMapper;

/** Creates a chat model client from a persisted model profile. */
public interface ModelProvider {
    String id();

    ChatModel create(ModelProfileData profile, ObjectMapper objectMapper);

    default ModelTokenizer tokenizer(ModelProfileData profile) {
        return ModelTokenizer.approximate();
    }
}
