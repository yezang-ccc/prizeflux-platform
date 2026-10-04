package com.prizeflux.campaign.infrastructure.observability;

import com.prizeflux.campaign.application.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxPressureMetricsTest {
    @Test
    void cachesOutboxPressureAndRetainsLastSnapshotOnFailure() {
        var registry = new SimpleMeterRegistry();
        var outbox = mock(OutboxRepository.class);
        var clock = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);
        var metrics = new OutboxPressureMetrics(outbox, registry, clock);
        when(outbox.pressureSnapshot()).thenReturn(new OutboxRepository.PressureSnapshot(4, 2, 1, 30_000));

        metrics.sample();

        assertThat(registry.get("prizeflux.outbox.pending").gauge().value()).isEqualTo(4);
        assertThat(registry.get("prizeflux.outbox.leased").gauge().value()).isEqualTo(2);
        assertThat(registry.get("prizeflux.outbox.dead").gauge().value()).isEqualTo(1);
        assertThat(registry.get("prizeflux.outbox.oldest.age").gauge().value()).isEqualTo(30_000);
        assertThat(registry.get("prizeflux.outbox.pressure.last.success.epoch").gauge().value())
                .isEqualTo(clock.instant().getEpochSecond());

        when(outbox.pressureSnapshot()).thenThrow(new IllegalStateException("synthetic failure"));
        metrics.sample();

        assertThat(registry.get("prizeflux.outbox.pressure.sample.failures").counter().count()).isEqualTo(1);
        assertThat(registry.get("prizeflux.outbox.pending").gauge().value()).isEqualTo(4);
    }

    @Test
    void exportsLowCardinalityPressureGaugesWithPrometheusNames() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var outbox = mock(OutboxRepository.class);
        var metrics = new OutboxPressureMetrics(outbox, registry,
                Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC));
        when(outbox.pressureSnapshot()).thenReturn(new OutboxRepository.PressureSnapshot(1, 2, 3, 4_000));

        metrics.sample();

        assertThat(registry.scrape())
                .contains("prizeflux_outbox_pending ")
                .contains("prizeflux_outbox_leased ")
                .contains("prizeflux_outbox_dead ")
                .contains("prizeflux_outbox_oldest_age_milliseconds")
                .contains("prizeflux_outbox_pressure_last_success_epoch_seconds");
    }
}
