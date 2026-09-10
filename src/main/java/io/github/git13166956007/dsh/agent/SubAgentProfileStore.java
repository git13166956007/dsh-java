package io.github.git13166956007.dsh.agent;

import java.util.List;

public interface SubAgentProfileStore {
    List<SubAgentProfileData> list() throws Exception;

    void save(SubAgentProfileData profile) throws Exception;

    void delete(String id) throws Exception;
}
