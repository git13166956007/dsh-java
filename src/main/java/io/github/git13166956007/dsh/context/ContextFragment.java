package io.github.git13166956007.dsh.context;

public record ContextFragment(String providerId, String title, String content, int priority) {
    public ContextFragment(String title, String content, int priority) {
        this(null, title, content, priority);
    }

    public ContextFragment {
        if (title == null || title.isBlank()) title = "Context";
        if (content == null || content.isBlank()) throw new IllegalArgumentException("context content must not be blank");
    }

    ContextFragment withProvider(String id) {
        return new ContextFragment(id, title, content, priority);
    }
}
