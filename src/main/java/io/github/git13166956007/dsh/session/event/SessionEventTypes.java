package io.github.git13166956007.dsh.session.event;

public final class SessionEventTypes {
    public static final String CREATED = "session.created";
    public static final String RENAMED = "session.renamed";
    public static final String USER_MESSAGE = "user.message";
    public static final String ASSISTANT_MESSAGE = "assistant.message";
    public static final String TOOL_MESSAGE = "tool.message";
    public static final String SUMMARY_UPDATED = "session.summary.updated";
    public static final String DELETED = "session.deleted";

    private SessionEventTypes() { }
}
