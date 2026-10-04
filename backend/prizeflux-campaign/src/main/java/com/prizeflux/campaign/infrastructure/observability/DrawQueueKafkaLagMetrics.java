package com.prizeflux.campaign.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Scrapes read cached values, never contact Kafka. No business identifiers are labels. */
@Component
@Profile("local-full")
@ConditionalOnProperty(name = {"prizeflux.kafka.enabled", "prizeflux.kafka.draw-queue-enabled",
        "prizeflux.kafka.draw-queue-consumer-enabled"}, havingValue = "true")
final class DrawQueueKafkaLagMetrics {
    private static final Logger log = LoggerFactory.getLogger(DrawQueueKafkaLagMetrics.class);
    private final DrawQueueKafkaLagReader reader;
    private final Clock clock;
    private final AtomicLong lag = new AtomicLong(-1);
    private final AtomicLong lastSuccess = new AtomicLong();
    private final Counter failures;
    private final AtomicBoolean sampling = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "draw-queue-kafka-lag-sampler");
        thread.setDaemon(true);
        return thread;
    });

    DrawQueueKafkaLagMetrics(DrawQueueKafkaLagReader reader, MeterRegistry registry, Clock clock) {
        this.reader = reader;
        this.clock = clock;
        registry.gauge("prizeflux.draw.queue.kafka.consumer.lag", lag);
        registry.gauge("prizeflux.draw.queue.kafka.consumer.lag.last.success.epoch.seconds", lastSuccess);
        failures = registry.counter("prizeflux.draw.queue.kafka.consumer.lag.sample.failures");
    }

    @Scheduled(fixedDelayString = "${prizeflux.metrics.draw-queue.kafka-lag-poll-ms:10000}",
            initialDelayString = "${prizeflux.metrics.draw-queue.kafka-lag-poll-ms:10000}")
    public void scheduleSample() {
        // Broker timeouts must not block the shared scheduler running the Outbox Relay.
        // The guard bounds both active and queued sampling work to a single task.
        if (closed.get() || !sampling.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    sampleNow();
                } finally {
                    sampling.set(false);
                }
            });
        } catch (RejectedExecutionException rejected) {
            sampling.set(false);
            if (!closed.get()) failed(rejected);
        }
    }

    public void sampleNow() {
        try {
            long value = reader.readLag();
            if (value < 0) throw new IllegalStateException("Negative draw queue lag");
            lag.set(value);
            lastSuccess.set(clock.instant().getEpochSecond());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failed(interrupted);
        } catch (Exception failure) {
            failed(failure);
        }
    }

    private void failed(Exception failure) {
        failures.increment();
        log.warn("draw_queue_kafka_lag_sample_failed failureType={}", failure.getClass().getSimpleName());
    }

    @PreDestroy
    void close() {
        closed.set(true);
        executor.shutdownNow();
    }
}
