package com.prizeflux.campaign.application;

/** 已持久化结果；重放必须读取同一个 ID 和相同结果。 */
public record DrawResult(long id, long campaignId, long userId, DrawOutcome outcome,
                         Long prizeId, String fulfillmentStatus) {
}
