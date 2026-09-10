package io.github.git13166956007.dsh.tool;

import java.util.List;

public interface ToolProfileStore {
    List<ToolProfileData> list() throws Exception;

    void save(ToolProfileData profile) throws Exception;

    void delete(String name) throws Exception;
}
