package io.github.git13166956007.dsh.agent;

import java.time.Instant;

public record SubAgentSession(
        String id,
        String profileId,
        String conversationId,
        SubAgentSessionStatus status,
        Instant createdAt,
        Instant updatedAt) {
    static SubAgentSession from(SubAgentSessionData data) {
        return new SubAgentSession(data.id(), data.profileId(), data.conversationId(), data.status(),
                data.createdAt(), data.updatedAt());
    }
}
