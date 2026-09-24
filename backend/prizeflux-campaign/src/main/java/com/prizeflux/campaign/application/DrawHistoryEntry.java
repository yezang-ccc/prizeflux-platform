package com.prizeflux.campaign.application;

import java.time.Instant;

/**
 * 个人抽奖历史的只读投影，与事务写入模型 {@link DrawResult} 分离：
 * 额外携带展示所需的下单时间 {@code drawnAt} 与发奖状态 {@code fulfillmentStatus}。
 */
public record DrawHistoryEntry(long id, long campaignId, DrawOutcome outcome,
                               Long prizeId, String fulfillmentStatus, Instant drawnAt) {
}
