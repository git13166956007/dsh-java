package io.github.git13166956007.dsh.event;

import java.util.List;

public interface EventJournal {
    void append(EventRecord record) throws Exception;

    List<EventRecord> read() throws Exception;

    /** Reads a bounded page after an opaque, monotonically increasing cursor. */
    default EventJournalPage read(long afterCursor, int limit) throws Exception {
        if (afterCursor < 0) throw new IllegalArgumentException("event journal cursor must not be negative");
        if (limit <= 0) throw new IllegalArgumentException("event journal page size must be positive");
        List<EventRecord> all = read();
        int from = (int) Math.min(afterCursor, all.size());
        int to = Math.min(all.size(), from + limit);
        return new EventJournalPage(all.subList(from, to), to, to < all.size());
    }
}
