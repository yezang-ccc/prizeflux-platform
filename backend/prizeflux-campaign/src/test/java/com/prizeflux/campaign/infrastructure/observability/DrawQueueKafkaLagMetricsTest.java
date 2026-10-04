package com.prizeflux.campaign.infrastructure.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DrawQueueKafkaLagMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Instant now = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void scrapeDoesNotQueryKafkaAndFailureRetainsCachedLagAndFreshness() {
        var calls = new AtomicInteger();
        var sampler = sampler(() -> {
            if (calls.getAndIncrement() == 0) return 12;
            throw new IllegalStateException("broker down");
        });
        assertThat(lag()).isEqualTo(-1);
        assertThat(calls.get()).isZero();
        sampler.sampleNow();
        sampler.sampleNow();
        assertThat(lag()).isEqualTo(12);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(registry.get("prizeflux.draw.queue.kafka.consumer.lag.sample.failures")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("prizeflux.draw.queue.kafka.consumer.lag.last.success.epoch.seconds")
                .gauge().value()).isEqualTo(now.getEpochSecond());
    }

    @Test
    void negativeSampleCannotBeRecordedAsAValidEmptyQueue() {
        sampler(() -> -2).sampleNow();
        assertThat(lag()).isEqualTo(-1);
        assertThat(registry.get("prizeflux.draw.queue.kafka.consumer.lag.last.success.epoch.seconds")
                .gauge().value()).isZero();
    }

    @Test
    void interruptedSamplerRetainsInterruptAndUnknownLag() {
        try {
            sampler(() -> { throw new InterruptedException(); }).sampleNow();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(lag()).isEqualTo(-1);
        } finally {
            Thread.interrupted();
        }
    }

    private DrawQueueKafkaLagMetrics sampler(DrawQueueKafkaLagReader reader) {
        return new DrawQueueKafkaLagMetrics(reader, registry, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void scheduledSlowBrokerIsOffloadedAndCannotAccumulateSamplingTasks() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var sampler = sampler(() -> {
            calls.incrementAndGet();
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return 3;
        });
        try {
            sampler.scheduleSample();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 20; i++) sampler.scheduleSample();
            assertThat(calls.get()).isEqualTo(1);
            assertThat(lag()).isEqualTo(-1);
        } finally {
            release.countDown();
            sampler.close();
        }
        sampler.scheduleSample();
        assertThat(calls.get()).isEqualTo(1);
    }

    private double lag() {
        return registry.get("prizeflux.draw.queue.kafka.consumer.lag").gauge().value();
    }
}
