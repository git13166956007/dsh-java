package io.github.git13166956007.dsh.event;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InMemoryEventJournal implements EventJournal {
    private final Map<String, EventRecord> records = new LinkedHashMap<String, EventRecord>();

    @Override
    public synchronized void append(EventRecord record) {
        records.putIfAbsent(record.id(), record);
    }

    @Override
    public synchronized List<EventRecord> read() {
        return List.copyOf(new ArrayList<EventRecord>(records.values()));
    }
}
