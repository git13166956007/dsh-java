package io.github.git13166956007.dsh.session.event;

import io.github.git13166956007.dsh.agent.ChatMessage;
import java.util.ArrayList;
import java.util.List;

public final class SessionEventProjection {
    private SessionEventProjection() { }

    public static List<ChatMessage> messages(List<SessionEvent> events) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        for (SessionEvent event : events) {
            if (SessionEventTypes.CREATED.equals(event.type())) {
                messages.clear();
                continue;
            }
            if (SessionEventTypes.DELETED.equals(event.type())) {
                messages.clear();
                continue;
            }
            if (SessionEventTypes.USER_MESSAGE.equals(event.type())
                    || SessionEventTypes.ASSISTANT_MESSAGE.equals(event.type())
                    || SessionEventTypes.TOOL_MESSAGE.equals(event.type())) {
                messages.add(SessionEventCodec.readMessage(event.payload()));
            }
        }
        return List.copyOf(messages);
    }
}
