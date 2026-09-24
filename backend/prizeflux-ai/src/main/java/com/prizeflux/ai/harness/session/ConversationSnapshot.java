package com.prizeflux.ai.harness.session;

import com.prizeflux.ai.harness.model.AgentMessage;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable session state. Events are append-only; no bearer token is represented here. */
public record ConversationSnapshot(SessionId sessionId, long ownerUserId, long version,
                                   Instant createdAt, Instant updatedAt, List<AgentMessage> events) {
    public ConversationSnapshot {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        events = List.copyOf(events);
        if (ownerUserId <= 0 || version < 0 || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("invalid conversation snapshot");
        }
    }

    public int characterCount() {
        return events.stream().mapToInt(SessionTextSize::of).sum();
    }
}
