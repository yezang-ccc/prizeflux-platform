package com.prizeflux.campaign.application;

/** Kafka 消息只携带定位提示；消费者仍须从 MySQL 加载权威任务与身份。 */
public record DrawQueueNotification(long queueRequestId) {
    public DrawQueueNotification {
        if (queueRequestId <= 0) throw new IllegalArgumentException("queueRequestId must be positive");
    }
}
