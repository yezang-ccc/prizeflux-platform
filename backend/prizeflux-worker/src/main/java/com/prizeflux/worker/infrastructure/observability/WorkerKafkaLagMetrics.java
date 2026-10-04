package com.prizeflux.worker.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

/** Cached low-cardinality gauges; a Prometheus scrape never performs a Kafka Admin request. */
@Component
@Profile("local-full")
public final class WorkerKafkaLagMetrics {
    private static final Logger log = LoggerFactory.getLogger(WorkerKafkaLagMetrics.class);

    private final KafkaConsumerLagReader reader;
    private final Clock clock;
    private final AtomicLong cachedLag = new AtomicLong(-1L);
    private final AtomicLong lastSuccessfulSampleEpochSeconds = new AtomicLong();
    private final Counter sampleFailures;

    WorkerKafkaLagMetrics(KafkaConsumerLagReader reader, MeterRegistry registry, Clock clock) {
        this.reader = reader;
        this.clock = clock;
        registry.gauge("prizeflux.worker.kafka.consumer.lag", cachedLag);
        registry.gauge("prizeflux.worker.kafka.consumer.lag.last.success.epoch.seconds", lastSuccessfulSampleEpochSeconds);
        this.sampleFailures = Counter.builder("prizeflux.worker.kafka.consumer.lag.sample.failures")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${prizeflux.worker.metrics.kafka-lag-poll-ms:10000}",
            initialDelayString = "${prizeflux.worker.metrics.kafka-lag-poll-ms:10000}")
    public void sampleNow() {
        try {
            long lag = reader.readLag();
            if (lag < 0) {
                throw new IllegalStateException("Kafka consumer lag cannot be negative");
            }
            cachedLag.set(lag);
            lastSuccessfulSampleEpochSeconds.set(clock.instant().getEpochSecond());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sampleFailures.increment();
            log.warn("worker_kafka_lag_sample_failed failureType={}", interrupted.getClass().getSimpleName());
        } catch (Exception failure) {
            sampleFailures.increment();
            log.warn("worker_kafka_lag_sample_failed failureType={}", failure.getClass().getSimpleName());
        }
    }
}
