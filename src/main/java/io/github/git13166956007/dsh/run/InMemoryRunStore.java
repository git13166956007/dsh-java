package io.github.git13166956007.dsh.run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryRunStore implements RunStore {
    private final Map<String, RunData> runs = new LinkedHashMap<String, RunData>();
    private final Map<String, List<RunEventData>> events = new LinkedHashMap<String, List<RunEventData>>();
    private final AtomicLong eventIds = new AtomicLong();

    @Override
    public synchronized List<RunData> listRuns() {
        return runs.values().stream().sorted(Comparator.comparing(RunData::startedAt).reversed()).toList();
    }

    @Override
    public synchronized List<RunEventData> listEvents(String runId) {
        return new ArrayList<RunEventData>(events.getOrDefault(runId, List.of()));
    }

    @Override
    public synchronized void saveRun(RunData run) {
        runs.put(run.id(), run);
    }

    @Override
    public synchronized void saveEvent(RunEventData event) {
        events.computeIfAbsent(event.runId(), ignored -> new ArrayList<RunEventData>()).add(event);
    }

    public long nextEventId() {
        return eventIds.incrementAndGet();
    }
}
