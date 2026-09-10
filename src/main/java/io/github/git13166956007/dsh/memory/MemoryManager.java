package io.github.git13166956007.dsh.memory;

import java.util.List;
import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ChatModel;
import io.github.git13166956007.dsh.agent.ModelResponse;
import io.github.git13166956007.dsh.tool.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class MemoryManager {
    private final MemoryStore store;
    private final ObjectMapper objectMapper;

    public MemoryManager(MemoryStore store) {
        this(store, new ObjectMapper());
    }

    public MemoryManager(MemoryStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public List<MemoryRecord> list(String namespace, String subjectKey, int limit) throws Exception {
        return store.list(required(namespace, "namespace"), required(subjectKey, "subjectKey"), limit)
                .stream().map(MemoryRecord::from).toList();
    }

    public List<MemoryRecord> search(String namespace, String subjectKey, String query, int limit) throws Exception {
        return store.search(required(namespace, "namespace"), required(subjectKey, "subjectKey"), query, limit)
                .stream().map(MemoryRecord::from).toList();
    }

    public MemoryRecord save(String namespace, String subjectKey, String type, String content,
                             String metadataJson, Double importance) throws Exception {
        String normalizedContent = required(content, "content");
        double score = importance == null ? 0.5 : importance;
        if (score < 0 || score > 1) throw new IllegalArgumentException("importance must be between 0 and 1");
        MemoryRecordData saved = store.save(new MemoryRecordData(0, required(namespace, "namespace"),
                required(subjectKey, "subjectKey"), type == null || type.isBlank() ? "fact" : type.trim(),
                normalizedContent, metadataJson, score, null, null));
        return MemoryRecord.from(saved);
    }

    public void delete(long id) throws Exception {
        store.delete(id);
    }

    public String context(String namespace, String subjectKey, String query, int limit) throws Exception {
        List<MemoryRecord> memories = search(namespace, subjectKey, query, limit);
        if (memories.isEmpty()) return "";
        StringBuilder result = new StringBuilder("Relevant memories:\n");
        for (MemoryRecord memory : memories) {
            result.append("- [").append(memory.memoryType()).append("] ").append(memory.content()).append('\n');
        }
        return result.toString().trim();
    }

    public List<MemoryRecord> extractAndSave(ChatModel model, String apiKey, String modelId,
                                              String namespace, String subjectKey, String prompt,
                                              String answer, int maxRecords) throws Exception {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        String normalizedNamespace = required(namespace, "namespace");
        String normalizedSubject = required(subjectKey, "subjectKey");
        String userPrompt = required(prompt, "prompt");
        if (maxRecords < 1 || maxRecords > 20) throw new IllegalArgumentException("maxRecords must be between 1 and 20");
        String transcript = "User:\n" + userPrompt + "\n\nAssistant:\n" + (answer == null ? "" : answer);
        ModelResponse response = model.complete(List.of(
                ChatMessage.system("Extract only durable user-specific memories from the conversation. "
                        + "Return JSON only in the form {\"memories\":[{\"type\":\"fact\","
                        + "\"content\":\"...\",\"importance\":0.5}]}. "
                        + "Use an empty array when there is nothing durable. Do not store transient task details,"
                        + " secrets, credentials, or your own response."),
                ChatMessage.user(transcript)), List.<ToolDefinition>of(), apiKey, modelId);
        JsonNode root = objectMapper.readTree(response.content() == null ? "{}" : response.content().trim());
        JsonNode values = root != null && root.isArray() ? root : root == null ? null : root.path("memories");
        if (values == null || values.isMissingNode() || values.isNull()) return List.of();
        if (!values.isArray()) throw new IllegalArgumentException("memory extractor returned invalid memories");
        List<MemoryRecordData> existing = new java.util.ArrayList<MemoryRecordData>(
                store.list(normalizedNamespace, normalizedSubject, 100));
        List<MemoryRecord> saved = new java.util.ArrayList<MemoryRecord>();
        for (JsonNode value : values) {
            if (saved.size() >= maxRecords) break;
            String content = required(value.path("content").asText(null), "memory content");
            if (content.length() > 2000 || content.matches(".*(?i)(sk-|api[_ -]?key|password|secret).*")) continue;
            if (existing.stream().anyMatch(memory -> content.equals(memory.content()))) continue;
            String type = value.path("type").asText("fact").trim().toLowerCase(java.util.Locale.ROOT);
            if (!type.matches("[a-z0-9_-]{1,32}")) type = "fact";
            double importance = value.path("importance").isNumber() ? value.path("importance").asDouble() : 0.5;
            if (Double.isNaN(importance) || Double.isInfinite(importance) || importance < 0 || importance > 1) continue;
            MemoryRecordData data = store.save(new MemoryRecordData(0, normalizedNamespace, normalizedSubject,
                    type, content, "{\"source\":\"auto\"}", importance, null, null));
            existing.add(data);
            saved.add(MemoryRecord.from(data));
        }
        return List.copyOf(saved);
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
