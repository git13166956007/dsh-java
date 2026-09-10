package io.github.git13166956007.dsh.model;

public interface ModelUsageStore {
    ModelUsageData find(String modelId) throws Exception;

    void save(ModelUsageData usage) throws Exception;

    void delete(String modelId) throws Exception;
}
