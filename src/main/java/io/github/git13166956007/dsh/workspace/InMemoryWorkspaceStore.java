package io.github.git13166956007.dsh.workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryWorkspaceStore implements WorkspaceStore {
    private final Map<String, WorkspaceProfileData> profiles = new LinkedHashMap<String, WorkspaceProfileData>();

    @Override
    public synchronized List<WorkspaceProfileData> list() {
        return new ArrayList<WorkspaceProfileData>(profiles.values());
    }

    @Override
    public synchronized void save(WorkspaceProfileData profile) {
        profiles.put(profile.id(), profile);
    }

    @Override
    public synchronized void delete(String id) {
        profiles.remove(id);
    }
}
