package io.github.git13166956007.dsh.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ContextSnapshot(List<ContextFragment> fragments, int estimatedTokens, int maxTokens,
                              boolean truncated, List<String> errors) {
    public ContextSnapshot {
        fragments = Collections.unmodifiableList(new ArrayList<ContextFragment>(fragments == null ? List.of() : fragments));
        errors = Collections.unmodifiableList(new ArrayList<String>(errors == null ? List.of() : errors));
    }

    public String promptText() {
        StringBuilder result = new StringBuilder();
        for (ContextFragment fragment : fragments) {
            if (result.length() > 0) result.append("\n\n");
            result.append("Dynamic context [").append(fragment.providerId()).append("] ")
                    .append(fragment.title()).append(":\n").append(fragment.content());
        }
        return result.toString();
    }
}
