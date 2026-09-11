package io.github.git13166956007.dsh.event;

import java.util.List;

/** A bounded journal page and the cursor to use for the next read. */
public record EventJournalPage(List<EventRecord> records, long nextCursor, boolean hasMore) {
    public EventJournalPage {
        records = records == null ? List.of() : List.copyOf(records);
        if (nextCursor < 0) throw new IllegalArgumentException("event journal cursor must not be negative");
    }
}
