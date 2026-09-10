package io.github.git13166956007.dsh.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class InMemoryModelUsageStore implements ModelUsageStore {
    private final Map<String, ModelUsageData> usages = new LinkedHashMap<String, ModelUsageData>();

    @Override
    public synchronized ModelUsageData find(String modelId) {
        return usages.get(modelId);
    }

    @Override
    public synchronized void save(ModelUsageData usage) {
        usages.put(usage.modelId(), usage);
    }

    @Override
    public synchronized void delete(String modelId) {
        usages.remove(modelId);
    }
}
