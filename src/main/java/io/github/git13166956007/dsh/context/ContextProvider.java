package io.github.git13166956007.dsh.context;

import java.util.List;

/** Supplies request-scoped text that can be added to an agent system prompt. */
public interface ContextProvider {
    String id();

    List<ContextFragment> provide(ContextRequest request) throws Exception;
}
