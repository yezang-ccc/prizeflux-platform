package com.prizeflux.campaign.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbox Relay：周期性领取到期事件、事务外投递、按投递结果回写状态。
 * 领取与标记都对领取标识校验，进程崩溃后由 {@code releaseExpired} 让超时租约回到 PENDING，不会丢记录。
 * 传输由 {@link DomainEventPublisher} 提供，Kafka 实现待 D7-03 接入；本类只编排领取/投递/回写。
 */
@Service
@ConditionalOnProperty(name = "prizeflux.outbox.relay.enabled", havingValue = "true")
public class OutboxPublisher {
    private final OutboxRepository outbox;
    private final DomainEventPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final Duration leaseDuration;
    private final int maxAttempts;

    public OutboxPublisher(OutboxRepository outbox, DomainEventPublisher publisher, Clock clock,
            @Value("${prizeflux.outbox.relay.batch-size:50}") int batchSize,
            @Value("${prizeflux.outbox.relay.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${prizeflux.outbox.relay.max-attempts:3}") int maxAttempts) {
        if (batchSize < 1 || leaseDuration == null || leaseDuration.isZero()
                || leaseDuration.isNegative() || maxAttempts < 1) {
            throw new IllegalArgumentException("Invalid Outbox relay configuration");
        }
        this.outbox = outbox;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.maxAttempts = maxAttempts;
    }

    /** 单轮领取并投递；供定时任务或测试调用。返回本轮领取的事件数。 */
    public int publishDue() {
        Instant now = clock.instant();
        outbox.releaseExpired(now);
        String leaseToken = UUID.randomUUID().toString();
        List<OutboxEvent> batch = outbox.claim(batchSize, leaseToken, now.plus(leaseDuration), now);
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event);
                outbox.markSent(event.eventId(), leaseToken);
            } catch (Exception failure) {
                if (event.attempts() >= maxAttempts) {
                    outbox.markDead(event.eventId(), leaseToken);
                } else {
                    outbox.release(event.eventId(), leaseToken);
                }
            }
        }
        return batch.size();
    }
}
