package io.github.git13166956007.dsh.event;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class InMemoryEventJournal implements EventJournal {
    private final Map<String, EventRecord> records = new LinkedHashMap<String, EventRecord>();

    @Override
    public synchronized void append(EventRecord record) {
        EventRecord existing = records.get(record.id());
        if (existing == null) {
            records.put(record.id(), record);
            return;
        }
        if (!same(existing, record)) {
            throw new IllegalArgumentException("event ID was already used with different content: " + record.id());
        }
    }

    @Override
    public synchronized List<EventRecord> read() {
        return List.copyOf(new ArrayList<EventRecord>(records.values()));
    }

    @Override
    public synchronized EventJournalPage read(long afterCursor, int limit) {
        if (afterCursor < 0) throw new IllegalArgumentException("event journal cursor must not be negative");
        if (limit <= 0) throw new IllegalArgumentException("event journal page size must be positive");
        List<EventRecord> all = new ArrayList<EventRecord>(records.values());
        int from = (int) Math.min(afterCursor, all.size());
        int to = Math.min(all.size(), from + limit);
        return new EventJournalPage(all.subList(from, to), to, to < all.size());
    }

    private static boolean same(EventRecord left, EventRecord right) {
        return Objects.equals(left.eventName(), right.eventName())
                && Objects.equals(left.payload(), right.payload())
                && Objects.equals(left.value(), right.value())
                && left.accepted() == right.accepted()
                && Objects.equals(left.reason(), right.reason())
                && Objects.equals(left.error(), right.error());
    }
}
