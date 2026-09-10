package io.github.git13166956007.dsh.model;

import java.util.List;

public interface ModelProfileStore {
    List<ModelProfileData> list() throws Exception;

    void save(ModelProfileData profile) throws Exception;

    void delete(String id) throws Exception;
}
