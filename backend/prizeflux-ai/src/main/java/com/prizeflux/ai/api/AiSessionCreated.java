package com.prizeflux.ai.api;

import java.time.Instant;

public record AiSessionCreated(String sessionId, long version, Instant createdAt) {
}
