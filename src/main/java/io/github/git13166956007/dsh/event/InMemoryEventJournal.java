package io.github.git13166956007.dsh.event;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryEventJournal implements EventJournal {
    private final CopyOnWriteArrayList<EventRecord> records = new CopyOnWriteArrayList<EventRecord>();

    @Override
    public void append(EventRecord record) {
        records.add(record);
    }

    @Override
    public List<EventRecord> read() {
        return List.copyOf(new ArrayList<EventRecord>(records));
    }
}
