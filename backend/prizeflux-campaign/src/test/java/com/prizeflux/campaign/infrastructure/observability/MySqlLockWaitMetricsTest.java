package com.prizeflux.campaign.infrastructure.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MySqlLockWaitMetricsTest {
    @Test
    void cachesCurrentBlockingRelationshipsAndRetainsLastSampleOnFailure() {
        var registry = new SimpleMeterRegistry();
        var jdbc = mock(JdbcTemplate.class);
        var clock = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);
        var metrics = new MySqlLockWaitMetrics(registry, clock, jdbc);
        when(jdbc.queryForObject(MySqlLockWaitMetrics.SAMPLE_SQL, Long.class)).thenReturn(3L);

        metrics.sample();

        assertThat(registry.get("prizeflux.mysql.innodb.lock.wait.relationships").gauge().value()).isEqualTo(3);
        assertThat(registry.get("prizeflux.mysql.lock.wait.sample.last.success.epoch").gauge().value())
                .isEqualTo(clock.instant().getEpochSecond());

        when(jdbc.queryForObject(MySqlLockWaitMetrics.SAMPLE_SQL, Long.class))
                .thenThrow(new IllegalStateException("synthetic failure"));
        metrics.sample();

        assertThat(registry.get("prizeflux.mysql.lock.wait.sample.failures").counter().count()).isEqualTo(1);
        assertThat(registry.get("prizeflux.mysql.innodb.lock.wait.relationships").gauge().value()).isEqualTo(3);
    }

    @Test
    void exportsLowCardinalityPrometheusGauges() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var jdbc = mock(JdbcTemplate.class);
        var metrics = new MySqlLockWaitMetrics(registry, Clock.systemUTC(), jdbc);
        when(jdbc.queryForObject(MySqlLockWaitMetrics.SAMPLE_SQL, Long.class)).thenReturn(1L);

        metrics.sample();

        assertThat(registry.scrape())
                .contains("prizeflux_mysql_innodb_lock_wait_relationships ")
                .contains("prizeflux_mysql_lock_wait_sample_last_success_epoch_seconds ")
                .contains("prizeflux_mysql_lock_wait_sample_failures_total");
    }
}
