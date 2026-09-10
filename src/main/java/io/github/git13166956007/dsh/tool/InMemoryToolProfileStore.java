package io.github.git13166956007.dsh.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryToolProfileStore implements ToolProfileStore {
    private final Map<String, ToolProfileData> profiles = new LinkedHashMap<String, ToolProfileData>();

    @Override
    public synchronized List<ToolProfileData> list() {
        return new ArrayList<ToolProfileData>(profiles.values());
    }

    @Override
    public synchronized void save(ToolProfileData profile) {
        profiles.put(profile.name(), profile);
    }

    @Override
    public synchronized void delete(String name) {
        profiles.remove(name);
    }
}
