package com.prizeflux.campaign.infrastructure.messaging;

import com.prizeflux.campaign.application.OutboxPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性触发 Outbox Relay，使到期事件能被领取并投递到 Kafka。
 * 使用固定延迟（上一轮结束后再计时），避免上一轮投递未结束时下一轮立即开始。
 */
@Component
@ConditionalOnProperty(name = "prizeflux.outbox.relay.enabled", havingValue = "true")
public class OutboxRelayTask {
    private final OutboxPublisher publisher;

    public OutboxRelayTask(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${prizeflux.outbox.relay.fixed-delay:5000}")
    public void relay() {
        publisher.publishDue();
    }
}
