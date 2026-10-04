package com.prizeflux.campaign.application;

import java.time.Instant;

/** 用户可见的队列投影；未结束时不携带开奖结果。 */
public record DrawQueueView(String queueRequestId, String requestKey, String status,
                            DrawResult result, String errorCode, Instant createdAt,
                            Instant completedAt) {
    public static DrawQueueView from(DrawQueueRepository.Entry entry) {
        return new DrawQueueView(Long.toString(entry.id()), entry.requestKey(),
                entry.status().name(), entry.result(), entry.errorCode(),
                entry.createdAt(), entry.completedAt());
    }
}
