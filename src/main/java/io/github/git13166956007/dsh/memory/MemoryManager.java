package io.github.git13166956007.dsh.memory;

import java.util.List;

public final class MemoryManager {
    private final MemoryStore store;

    public MemoryManager(MemoryStore store) {
        this.store = store;
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

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
