package io.github.git13166956007.dsh.event;

import java.util.List;

public interface EventJournal {
    void append(EventRecord record) throws Exception;

    List<EventRecord> read() throws Exception;
}
