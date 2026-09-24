package com.prizeflux.contract.event;

import java.time.Instant;

/** 跨服务稳定事件载荷；不暴露 Campaign 数据库实体。 */
public record PrizeFulfillmentEvent(
        int schemaVersion,
        String eventId,
        long drawId,
        long campaignId,
        int configVersion,
        long userId,
        long prizeId,
        Instant occurredAt) {
    public PrizeFulfillmentEvent {
        if (schemaVersion != 1 || eventId == null || eventId.isBlank()
                || drawId <= 0 || campaignId <= 0 || configVersion <= 0
                || userId <= 0 || prizeId <= 0 || occurredAt == null) {
            throw new IllegalArgumentException("Invalid fulfillment event");
        }
    }
}
