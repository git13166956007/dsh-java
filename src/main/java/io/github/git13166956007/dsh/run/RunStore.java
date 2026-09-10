package io.github.git13166956007.dsh.run;

import java.util.List;

public interface RunStore {
    List<RunData> listRuns() throws Exception;

    List<RunEventData> listEvents(String runId) throws Exception;

    void saveRun(RunData run) throws Exception;

    void saveEvent(RunEventData event) throws Exception;
}
