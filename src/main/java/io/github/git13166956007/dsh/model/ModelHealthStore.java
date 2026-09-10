package io.github.git13166956007.dsh.model;

public interface ModelHealthStore {
    ModelHealthData find(String modelId) throws Exception;

    void save(ModelHealthData health) throws Exception;

    void delete(String modelId) throws Exception;
}
