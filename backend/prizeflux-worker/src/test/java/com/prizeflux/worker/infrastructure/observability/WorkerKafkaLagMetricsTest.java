package com.prizeflux.worker.infrastructure.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerKafkaLagMetricsTest {
    @Test
    void cachesSuccessfulLagAndKeepsItWhenLaterSampleFails() {
        var registry = new SimpleMeterRegistry();
        var calls = new AtomicInteger();
        KafkaConsumerLagReader reader = () -> {
            if (calls.getAndIncrement() == 0) {
                return 12L;
            }
            throw new IllegalStateException("broker unavailable");
        };
        var now = Instant.parse("2026-09-29T00:00:00Z");
        var sampler = new WorkerKafkaLagMetrics(reader, registry, Clock.fixed(now, ZoneOffset.UTC));

        sampler.sampleNow();
        sampler.sampleNow();

        assertEquals(12.0, registry.get("prizeflux.worker.kafka.consumer.lag").gauge().value());
        assertEquals(1.0, registry.get("prizeflux.worker.kafka.consumer.lag.sample.failures").counter().count());
        assertEquals((double) now.getEpochSecond(), registry.get("prizeflux.worker.kafka.consumer.lag.last.success.epoch.seconds")
                .gauge().value());
    }
}
