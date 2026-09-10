package io.github.git13166956007.dsh.model;

import io.github.git13166956007.dsh.agent.ChatModel;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** Creates a chat model client from a persisted model profile. */
public interface ModelProvider {
    String id();

    ChatModel create(ModelProfileData profile, ObjectMapper objectMapper);

    default ModelTokenizer tokenizer(ModelProfileData profile) {
        return ModelTokenizer.approximate();
    }

    /** Lists models exposed by this provider, when the provider supports discovery. */
    default List<ModelCatalogEntry> listModels(ModelProfileData profile, String requestApiKey,
                                               ObjectMapper objectMapper) throws Exception {
        throw new UnsupportedOperationException("model catalog is not supported by provider: " + id());
    }
}
