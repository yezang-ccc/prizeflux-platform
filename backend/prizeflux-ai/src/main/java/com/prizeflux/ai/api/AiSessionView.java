package com.prizeflux.ai.api;

import java.time.Instant;
import java.util.List;

public record AiSessionView(String sessionId, long version, Instant createdAt, Instant updatedAt,
                            List<AiSessionEventView> events) {
    public AiSessionView {
        events = List.copyOf(events);
    }
}
