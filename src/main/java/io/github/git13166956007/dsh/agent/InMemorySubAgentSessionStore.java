package io.github.git13166956007.dsh.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySubAgentSessionStore implements SubAgentSessionStore {
    private final Map<String, SubAgentSessionData> sessions = new ConcurrentHashMap<String, SubAgentSessionData>();

    @Override
    public List<SubAgentSessionData> list() {
        return sessions.values().stream().sorted(Comparator.comparing(SubAgentSessionData::createdAt).reversed())
                .toList();
    }

    @Override
    public SubAgentSessionData find(String id) {
        return sessions.get(id);
    }

    @Override
    public void save(SubAgentSessionData session) {
        sessions.put(session.id(), session);
    }

    @Override
    public void delete(String id) {
        sessions.remove(id);
    }
}
