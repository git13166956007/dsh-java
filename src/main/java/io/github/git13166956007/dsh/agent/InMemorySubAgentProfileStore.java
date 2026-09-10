package io.github.git13166956007.dsh.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemorySubAgentProfileStore implements SubAgentProfileStore {
    private final Map<String, SubAgentProfileData> profiles = new LinkedHashMap<String, SubAgentProfileData>();

    @Override
    public synchronized List<SubAgentProfileData> list() {
        return new ArrayList<SubAgentProfileData>(profiles.values());
    }

    @Override
    public synchronized void save(SubAgentProfileData profile) {
        profiles.put(profile.id(), profile);
    }

    @Override
    public synchronized void delete(String id) {
        profiles.remove(id);
    }
}
