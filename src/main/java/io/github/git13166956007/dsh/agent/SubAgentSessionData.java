package io.github.git13166956007.dsh.agent;

import java.time.Instant;

record SubAgentSessionData(
        String id,
        String profileId,
        String conversationId,
        SubAgentSessionStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
