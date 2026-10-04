package com.prizeflux.campaign.infrastructure.observability;

import com.prizeflux.campaign.application.OutboxTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerOutboxTelemetryTest {
    @Test
    void recordsBoundedDatabaseStagesIncludingFailureAndClampsNegativeDuration() {
        var registry = new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        var telemetry = new MicrometerOutboxTelemetry(registry);
        telemetry.recordDatabaseStage(OutboxTelemetry.DatabaseStage.CLAIM,
                OutboxTelemetry.StageOutcome.SUCCESS, 4_000_000L);
        telemetry.recordDatabaseStage(OutboxTelemetry.DatabaseStage.MARK_SENT,
                OutboxTelemetry.StageOutcome.FAILURE, -1L);
        assertThat(registry.get("prizeflux.outbox.database.stage")
                .tags("stage", "claim", "outcome", "success").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(4.0);
        assertThat(registry.get("prizeflux.outbox.database.stage")
                .tags("stage", "mark_sent", "outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(registry.get("prizeflux.outbox.database.stage")
                .tags("stage", "mark_sent", "outcome", "failure").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isZero();
        assertThat(registry.scrape()).contains("prizeflux_outbox_database_stage_seconds_count",
                "prizeflux_outbox_database_stage_seconds_sum");
    }

    @Test
    void recordsPublishOutcomeLatencyConfirmationAgeAndLeaseReclaimCount() {
        var registry = new SimpleMeterRegistry();
        var telemetry = new MicrometerOutboxTelemetry(registry);

        telemetry.recordPublish(OutboxTelemetry.PublishOutcome.SENT, 2_000_000L, 90_000_000L);
        telemetry.recordPublish(OutboxTelemetry.PublishOutcome.RETRY_SCHEDULED, 3_000_000L, -1L);
        telemetry.recordLeaseReclaimed(4);

        assertThat(registry.get("prizeflux.outbox.publish.events")
                .tag("outcome", "sent").counter().count()).isEqualTo(1);
        assertThat(registry.get("prizeflux.outbox.publish.events")
                .tag("outcome", "retry_scheduled").counter().count()).isEqualTo(1);
        assertThat(registry.get("prizeflux.outbox.publish")
                .tag("outcome", "sent").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2.0);
        assertThat(registry.get("prizeflux.outbox.create.to.confirm")
                .timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(90.0);
        assertThat(registry.get("prizeflux.outbox.leases.reclaimed")
                .counter().count()).isEqualTo(4);
    }
}
