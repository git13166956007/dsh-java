package io.github.git13166956007.dsh.agent;

import java.util.List;

public interface SubAgentSessionStore {
    List<SubAgentSessionData> list() throws Exception;

    SubAgentSessionData find(String id) throws Exception;

    void save(SubAgentSessionData session) throws Exception;

    void delete(String id) throws Exception;
}
