package io.github.git13166956007.dsh.skill;

import java.util.Map;

public interface SkillStateStore {
    Map<String, Boolean> list() throws Exception;

    void save(String skillId, boolean enabled) throws Exception;

    default void delete(String skillId) {
    }
}
