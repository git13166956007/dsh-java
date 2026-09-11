package io.github.git13166956007.dsh.run;

import java.util.List;

public interface RunStore {
    List<RunData> listRuns() throws Exception;

    List<RunEventData> listEvents(String runId) throws Exception;

    void saveRun(RunData run) throws Exception;

    default boolean compareAndSetStatus(RunData expected, RunData next) throws Exception {
        saveRun(next);
        return true;
    }

    /** Transitions a run and appends its lifecycle event as one store operation when supported. */
    default RunEventData compareAndSetStatusAndEvent(RunData expected, RunData next, RunEventData event)
            throws Exception {
        if (!compareAndSetStatus(expected, next)) return null;
        return saveEvent(event);
    }

    RunEventData saveEvent(RunEventData event) throws Exception;
}
