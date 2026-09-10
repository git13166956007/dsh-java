package io.github.git13166956007.dsh.memory;

import java.time.Instant;

record MemoryRecordData(
        long id,
        String namespace,
        String subjectKey,
        String memoryType,
        String content,
        String metadataJson,
        double importance,
        Instant createdAt,
        Instant updatedAt) {
}
