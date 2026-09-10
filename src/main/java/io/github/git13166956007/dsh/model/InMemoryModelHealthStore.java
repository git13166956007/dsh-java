package io.github.git13166956007.dsh.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class InMemoryModelHealthStore implements ModelHealthStore {
    private final Map<String, ModelHealthData> health = new LinkedHashMap<String, ModelHealthData>();

    @Override
    public synchronized ModelHealthData find(String modelId) {
        return health.get(modelId);
    }

    @Override
    public synchronized void save(ModelHealthData value) {
        health.put(value.modelId(), value);
    }

    @Override
    public synchronized void delete(String modelId) {
        health.remove(modelId);
    }
}
