package io.github.git13166956007.dsh.skill;

import java.util.LinkedHashMap;
import java.util.Map;

public final class InMemorySkillStateStore implements SkillStateStore {
    private final Map<String, Boolean> states = new LinkedHashMap<String, Boolean>();

    @Override
    public synchronized Map<String, Boolean> list() {
        return new LinkedHashMap<String, Boolean>(states);
    }

    @Override
    public synchronized void save(String skillId, boolean enabled) {
        states.put(skillId, enabled);
    }

    @Override
    public synchronized void delete(String skillId) {
        states.remove(skillId);
    }
}
