package com.prizeflux.campaign.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbox Relay：周期性领取到期事件、事务外投递、按投递结果回写状态。
 * 领取与标记都对领取标识校验，进程崩溃后由 {@code releaseExpired} 让超时租约回到 PENDING，不会丢记录。
 * 传输由 {@link DomainEventPublisher} 提供；本类只编排领取/投递/回写。
 */
@Service
@ConditionalOnProperty(name = "prizeflux.outbox.relay.enabled", havingValue = "true")
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final DomainEventPublisher publisher;
    private final OutboxTelemetry telemetry;
    private final Clock clock;
    private final int batchSize;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;
    private final BoundedOutboxDispatcher dispatcher;
    private final AtomicBoolean databaseTelemetryFailureLogged = new AtomicBoolean();

    public OutboxPublisher(OutboxRepository outbox, DomainEventPublisher publisher, OutboxTelemetry telemetry,
            Clock clock,
            @Value("${prizeflux.outbox.relay.batch-size:50}") int batchSize,
            @Value("${prizeflux.outbox.relay.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${prizeflux.outbox.relay.max-attempts:3}") int maxAttempts,
            @Value("${prizeflux.outbox.relay.retry-base-delay:PT2S}") Duration retryBaseDelay,
            @Value("${prizeflux.outbox.relay.retry-max-delay:PT30S}") Duration retryMaxDelay,
            BoundedOutboxDispatcher dispatcher) {
        if (batchSize < 1 || leaseDuration == null || leaseDuration.isZero()
                || leaseDuration.isNegative() || maxAttempts < 1
                || retryBaseDelay == null || retryBaseDelay.isZero() || retryBaseDelay.isNegative()
                || retryMaxDelay == null || retryMaxDelay.compareTo(retryBaseDelay) < 0
                || (dispatcher.isParallel() && leaseDuration.compareTo(Duration.ofSeconds(30)) < 0)) {
            throw new IllegalArgumentException("Invalid Outbox relay configuration");
        }
        this.outbox = outbox;
        this.publisher = publisher;
        this.telemetry = telemetry;
        this.clock = clock;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.maxAttempts = maxAttempts;
        this.retryBaseDelay = retryBaseDelay;
        this.retryMaxDelay = retryMaxDelay;
        this.dispatcher = dispatcher;
    }

    /** 单轮领取并投递；供定时任务或测试调用。返回本轮领取的事件数。 */
    public synchronized int publishDue() {
        if (dispatcher.isClosed() || Thread.currentThread().isInterrupted()) { return 0; }
        Instant now = clock.instant();
        recordLeaseReclaimed(measureDatabase(OutboxTelemetry.DatabaseStage.RECLAIM,
                () -> outbox.releaseExpired(now)));
        String leaseToken = UUID.randomUUID().toString();
        List<OutboxEvent> batch = measureDatabase(OutboxTelemetry.DatabaseStage.CLAIM,
                () -> outbox.claim(dispatcher.claimLimit(batchSize), leaseToken, now.plus(leaseDuration), now));
        dispatcher.dispatch(batch, event -> publishEvent(event, leaseToken));
        return batch.size();
    }

    private boolean publishEvent(OutboxEvent event, String leaseToken) {
        long started = System.nanoTime();
        OutboxTelemetry.PublishOutcome outcome = OutboxTelemetry.PublishOutcome.CLAIM_LOST;
        long createToConfirmNanos = -1L;
        try {
            publisher.publish(event);
            if (measureDatabase(OutboxTelemetry.DatabaseStage.MARK_SENT,
                    () -> outbox.markSent(event.eventId(), leaseToken))) {
                outcome = OutboxTelemetry.PublishOutcome.SENT;
                if (event.createdAt() != null) {
                    createToConfirmNanos = elapsedSinceCreationNanos(event.createdAt());
                }
            }
        } catch (Exception failure) {
            if (event.attempts() >= maxAttempts) {
                if (measureDatabase(OutboxTelemetry.DatabaseStage.MARK_DEAD,
                        () -> outbox.markDead(event.eventId(), leaseToken))) {
                    outcome = OutboxTelemetry.PublishOutcome.DEAD_LETTERED;
                }
            } else {
                Instant retryAt = clock.instant().plus(retryDelay(event.attempts()));
                if (measureDatabase(OutboxTelemetry.DatabaseStage.RELEASE_RETRY,
                        () -> outbox.release(event.eventId(), leaseToken, retryAt))) {
                    outcome = OutboxTelemetry.PublishOutcome.RETRY_SCHEDULED;
                }
            }
            log.warn("outbox_publish_failed eventId={} outcome={} failureType={}",
                    event.eventId(), outcome.name(), failure.getClass().getSimpleName());
            if (failure instanceof InterruptedException) {
                // Serial mode stops this batch; already dispatched parallel peers finish independently.
                // Unsent records recover through their leases without spending additional attempts.
                Thread.currentThread().interrupt();
                return false;
            }
        } finally {
            recordPublish(outcome, System.nanoTime() - started, createToConfirmNanos);
        }
        return true;
    }

    private Duration retryDelay(int attempts) {
        Duration delay = retryBaseDelay;
        for (int attempt = 1; attempt < attempts && delay.compareTo(retryMaxDelay) < 0; attempt++) {
            try {
                delay = delay.multipliedBy(2);
            } catch (ArithmeticException overflow) {
                return retryMaxDelay;
            }
        }
        return delay.compareTo(retryMaxDelay) > 0 ? retryMaxDelay : delay;
    }

    private void recordPublish(OutboxTelemetry.PublishOutcome outcome, long elapsedNanos,
            long createToConfirmNanos) {
        try {
            telemetry.recordPublish(outcome, elapsedNanos, createToConfirmNanos);
        } catch (RuntimeException failure) {
            log.warn("outbox_telemetry_failed operation=publish failureType={}",
                    failure.getClass().getSimpleName());
        }
    }

    private <T> T measureDatabase(OutboxTelemetry.DatabaseStage stage, Supplier<T> operation) {
        long started = System.nanoTime();
        OutboxTelemetry.StageOutcome outcome = OutboxTelemetry.StageOutcome.FAILURE;
        try {
            T result = operation.get();
            outcome = OutboxTelemetry.StageOutcome.SUCCESS;
            return result;
        } finally {
            try {
                telemetry.recordDatabaseStage(stage, outcome, System.nanoTime() - started);
            } catch (RuntimeException failure) {
                if (databaseTelemetryFailureLogged.compareAndSet(false, true)) {
                    log.warn("outbox_telemetry_failed operation=database_stage failureType={}",
                            failure.getClass().getSimpleName());
                }
            }
        }
    }

    private long elapsedSinceCreationNanos(Instant createdAt) {
        try {
            return Math.max(0L, Duration.between(createdAt, clock.instant()).toNanos());
        } catch (RuntimeException failure) {
            // Telemetry must never turn an already acknowledged event into a retry path.
            return -1L;
        }
    }

    private void recordLeaseReclaimed(long eventCount) {
        try {
            telemetry.recordLeaseReclaimed(eventCount);
        } catch (RuntimeException failure) {
            log.warn("outbox_telemetry_failed operation=lease_reclaim failureType={}",
                    failure.getClass().getSimpleName());
        }
    }
}
