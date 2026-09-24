package com.prizeflux.campaign.application;

/** 待发布的 Outbox 事件只读投影（含 Relay 重试计数），由 {@link OutboxRepository#claim} 返回。 */
public record OutboxEvent(String eventId, String eventType, long aggregateId,
                          int schemaVersion, String payload, int attempts) {
}
