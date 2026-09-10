package io.github.git13166956007.dsh.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryMemoryStore implements MemoryStore {
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, MemoryRecordData> memories = new LinkedHashMap<Long, MemoryRecordData>();

    @Override
    public synchronized List<MemoryRecordData> list(String namespace, String subjectKey, int limit) {
        return memories.values().stream().filter(memory -> matches(memory, namespace, subjectKey))
                .sorted(Comparator.comparing(MemoryRecordData::updatedAt).reversed())
                .limit(limit).toList();
    }

    @Override
    public synchronized List<MemoryRecordData> search(String namespace, String subjectKey, String query, int limit) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        return memories.values().stream().filter(memory -> matches(memory, namespace, subjectKey))
                .filter(memory -> normalized.isEmpty() || memory.content().toLowerCase().contains(normalized))
                .sorted(Comparator.comparingDouble(MemoryRecordData::importance).reversed()
                        .thenComparing(MemoryRecordData::updatedAt, Comparator.reverseOrder()))
                .limit(limit).toList();
    }

    @Override
    public synchronized MemoryRecordData save(MemoryRecordData memory) {
        long id = memory.id() > 0 ? memory.id() : ids.incrementAndGet();
        Instant now = Instant.now();
        MemoryRecordData saved = new MemoryRecordData(id, memory.namespace(), memory.subjectKey(), memory.memoryType(),
                memory.content(), memory.metadataJson(), memory.importance(),
                memory.createdAt() == null ? now : memory.createdAt(), now);
        memories.put(id, saved);
        return saved;
    }

    @Override
    public synchronized void delete(long id) {
        memories.remove(id);
    }

    private static boolean matches(MemoryRecordData memory, String namespace, String subjectKey) {
        return memory.namespace().equals(namespace) && memory.subjectKey().equals(subjectKey);
    }
}
