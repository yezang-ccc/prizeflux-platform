package com.prizeflux.campaign.application;

import java.time.Instant;

/** 待发布的 Outbox 事件只读投影（含 Relay 重试计数），由 {@link OutboxRepository#claim} 返回。 */
public record OutboxEvent(String eventId, String eventType, long aggregateId,
                          int schemaVersion, String payload, int attempts, Instant createdAt) {
    /** Compatibility constructor for focused tests/fakes that do not model database creation time. */
    public OutboxEvent(String eventId, String eventType, long aggregateId,
            int schemaVersion, String payload, int attempts) {
        this(eventId, eventType, aggregateId, schemaVersion, payload, attempts, null);
    }
}
