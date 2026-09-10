package io.github.git13166956007.dsh.memory;

import java.time.Instant;

public record MemoryRecord(
        long id,
        String namespace,
        String subjectKey,
        String memoryType,
        String content,
        String metadataJson,
        double importance,
        Instant createdAt,
        Instant updatedAt) {
    static MemoryRecord from(MemoryRecordData data) {
        return new MemoryRecord(data.id(), data.namespace(), data.subjectKey(), data.memoryType(), data.content(),
                data.metadataJson(), data.importance(), data.createdAt(), data.updatedAt());
    }
}
