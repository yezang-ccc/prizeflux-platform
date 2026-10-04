package com.prizeflux.campaign.infrastructure.observability;

import com.prizeflux.campaign.application.DrawQueueRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DrawQueuePressureMetricsTest {
    @Test
    void cachesQueuePressureAndRecordsSamplerFailureWithoutThrowing() {
        var registry = new SimpleMeterRegistry();
        var queue = mock(DrawQueueRepository.class);
        var clock = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);
        var metrics = new DrawQueuePressureMetrics(queue, registry, clock);
        when(queue.pressureSnapshot()).thenReturn(new DrawQueueRepository.PressureSnapshot(7, 12_500));

        metrics.sample();

        assertThat(registry.get("prizeflux.draw.queue.pending").gauge().value()).isEqualTo(7);
        assertThat(registry.get("prizeflux.draw.queue.oldest.age").gauge().value()).isEqualTo(12_500);
        assertThat(registry.get("prizeflux.draw.queue.pressure.last.success.epoch").gauge().value())
                .isEqualTo(clock.instant().getEpochSecond());

        when(queue.pressureSnapshot()).thenThrow(new IllegalStateException("synthetic failure"));
        metrics.sample();
        assertThat(registry.get("prizeflux.draw.queue.pressure.sample.failures").counter().count()).isEqualTo(1);
        assertThat(registry.get("prizeflux.draw.queue.pending").gauge().value()).isEqualTo(7);
    }

    @Test
    void exportsLowCardinalityPressureGaugesWithPrometheusNames() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var queue = mock(DrawQueueRepository.class);
        var clock = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);
        var metrics = new DrawQueuePressureMetrics(queue, registry, clock);
        when(queue.pressureSnapshot()).thenReturn(new DrawQueueRepository.PressureSnapshot(3, 1_500));

        metrics.sample();

        assertThat(registry.scrape())
                .contains("prizeflux_draw_queue_pending ")
                .contains("prizeflux_draw_queue_oldest_age")
                .contains("prizeflux_draw_queue_pressure_last_success_epoch_seconds");
    }
}
