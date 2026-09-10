package io.github.git13166956007.dsh.memory;

import java.util.List;

public interface MemoryStore {
    List<MemoryRecordData> list(String namespace, String subjectKey, int limit) throws Exception;

    List<MemoryRecordData> search(String namespace, String subjectKey, String query, int limit) throws Exception;

    MemoryRecordData save(MemoryRecordData memory) throws Exception;

    void delete(long id) throws Exception;
}
