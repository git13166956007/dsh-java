package io.github.git13166956007.dsh.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryAgentProfileStore implements AgentProfileStore {
    private final Map<String, AgentProfileData> profiles = new LinkedHashMap<String, AgentProfileData>();

    @Override
    public synchronized List<AgentProfileData> list() {
        return new ArrayList<AgentProfileData>(profiles.values());
    }

    @Override
    public synchronized void save(AgentProfileData profile) {
        profiles.put(profile.id(), profile);
    }

    @Override
    public synchronized void delete(String id) {
        profiles.remove(id);
    }
}
