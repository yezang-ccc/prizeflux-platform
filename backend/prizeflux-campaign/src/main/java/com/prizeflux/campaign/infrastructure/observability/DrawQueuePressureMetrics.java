package com.prizeflux.campaign.infrastructure.observability;

import com.prizeflux.campaign.application.DrawQueueRepository;
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

/** Low-frequency DB sampler; scrape requests read cached gauges and never execute queue COUNT/MIN SQL. */
@Component
@ConditionalOnProperty(name = "prizeflux.observability.draw-queue.enabled", havingValue = "true")
public final class DrawQueuePressureMetrics {
    private static final Logger log = LoggerFactory.getLogger(DrawQueuePressureMetrics.class);

    private final DrawQueueRepository queue;
    private final Clock clock;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeMillis = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final Counter sampleFailures;

    public DrawQueuePressureMetrics(DrawQueueRepository queue, MeterRegistry registry, Clock clock) {
        this.queue = queue;
        this.clock = clock;
        Gauge.builder("prizeflux.draw.queue.pending", pending, AtomicLong::get)
                .description("Sampled count of non-terminal draw queue requests")
                .register(registry);
        Gauge.builder("prizeflux.draw.queue.oldest.age", oldestAgeMillis, AtomicLong::get)
                .description("Sampled age in milliseconds of the oldest non-terminal draw queue request")
                .baseUnit("milliseconds")
                .register(registry);
        Gauge.builder("prizeflux.draw.queue.pressure.last.success.epoch", lastSuccessEpochSeconds, AtomicLong::get)
                .description("Unix timestamp in seconds of the last successful queue pressure sample")
                .baseUnit("seconds")
                .register(registry);
        this.sampleFailures = registry.counter("prizeflux.draw.queue.pressure.sample.failures");
    }

    @Scheduled(fixedDelayString = "${prizeflux.observability.draw-queue.sample-delay-ms:10000}")
    public void sample() {
        try {
            DrawQueueRepository.PressureSnapshot snapshot = queue.pressureSnapshot();
            pending.set(snapshot.pendingCount());
            oldestAgeMillis.set(snapshot.oldestAgeMillis());
            lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
        } catch (RuntimeException failure) {
            sampleFailures.increment();
            log.warn("draw_queue_pressure_sample_failed failureType={}",
                    failure.getClass().getSimpleName());
        }
    }
}
