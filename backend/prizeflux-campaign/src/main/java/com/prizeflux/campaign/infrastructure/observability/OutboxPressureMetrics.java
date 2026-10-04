package com.prizeflux.campaign.infrastructure.observability;

import com.prizeflux.campaign.application.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

/** Low-frequency DB sampler; Prometheus scrapes read these cached gauges rather than querying MySQL. */
@Component
@ConditionalOnProperty(name = "prizeflux.observability.outbox.enabled", havingValue = "true")
public final class OutboxPressureMetrics {
    private static final Logger log = LoggerFactory.getLogger(OutboxPressureMetrics.class);

    private final OutboxRepository outbox;
    private final Clock clock;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong leased = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final AtomicLong oldestAgeMillis = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final Counter sampleFailures;

    public OutboxPressureMetrics(OutboxRepository outbox, MeterRegistry registry, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
        Gauge.builder("prizeflux.outbox.pending", pending, AtomicLong::get)
                .description("Sampled number of pending Outbox events")
                .register(registry);
        Gauge.builder("prizeflux.outbox.leased", leased, AtomicLong::get)
                .description("Sampled number of leased Outbox events")
                .register(registry);
        Gauge.builder("prizeflux.outbox.dead", dead, AtomicLong::get)
                .description("Sampled number of dead-lettered Outbox events requiring attention")
                .register(registry);
        Gauge.builder("prizeflux.outbox.oldest.age", oldestAgeMillis, AtomicLong::get)
                .description("Sampled age in milliseconds of the oldest pending or leased Outbox event")
                .baseUnit("milliseconds")
                .register(registry);
        Gauge.builder("prizeflux.outbox.pressure.last.success.epoch", lastSuccessEpochSeconds, AtomicLong::get)
                .description("Unix timestamp in seconds of the last successful Outbox pressure sample")
                .baseUnit("seconds")
                .register(registry);
        this.sampleFailures = registry.counter("prizeflux.outbox.pressure.sample.failures");
    }

    @Scheduled(fixedDelayString = "${prizeflux.observability.outbox.sample-delay-ms:10000}")
    public void sample() {
        try {
            OutboxRepository.PressureSnapshot snapshot = outbox.pressureSnapshot();
            pending.set(snapshot.pendingCount());
            leased.set(snapshot.leasedCount());
            dead.set(snapshot.deadCount());
            oldestAgeMillis.set(snapshot.oldestAgeMillis());
            lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
        } catch (RuntimeException failure) {
            sampleFailures.increment();
            log.warn("outbox_pressure_sample_failed failureType={}", failure.getClass().getSimpleName());
        }
    }
}
