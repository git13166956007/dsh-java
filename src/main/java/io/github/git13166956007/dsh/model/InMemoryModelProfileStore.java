package io.github.git13166956007.dsh.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryModelProfileStore implements ModelProfileStore {
    private final Map<String, ModelProfileData> profiles = new LinkedHashMap<String, ModelProfileData>();

    @Override
    public synchronized List<ModelProfileData> list() {
        return new ArrayList<ModelProfileData>(profiles.values());
    }

    @Override
    public synchronized void save(ModelProfileData profile) {
        profiles.put(profile.id(), profile);
    }

    @Override
    public synchronized void delete(String id) {
        profiles.remove(id);
    }
}
