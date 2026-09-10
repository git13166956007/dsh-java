package io.github.git13166956007.dsh.workspace;

import java.util.List;

public interface WorkspaceStore {
    List<WorkspaceProfileData> list() throws Exception;

    void save(WorkspaceProfileData profile) throws Exception;

    void delete(String id) throws Exception;
}
