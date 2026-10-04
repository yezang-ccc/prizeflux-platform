package com.prizeflux.worker.infrastructure.observability;

import com.prizeflux.worker.application.FulfillmentTelemetry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrometerFulfillmentTelemetryTest {
    @Test
    void exportsBoundedWorkerOutcomesAndTimersWithoutIdentifiers() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, new PrometheusRegistry(),
                io.micrometer.core.instrument.Clock.SYSTEM);
        var telemetry = new MicrometerFulfillmentTelemetry(registry);

        telemetry.recordConsume(FulfillmentTelemetry.ConsumeOutcome.PROCESSED, 1_000_000L);
        telemetry.recordConsume(FulfillmentTelemetry.ConsumeOutcome.DUPLICATE, 2_000_000L);
        telemetry.recordExecution(FulfillmentTelemetry.ExecutionOutcome.RETRY_SCHEDULED, 3_000_000L);
        telemetry.recordExecution(FulfillmentTelemetry.ExecutionOutcome.MANUAL_REVIEW, 4_000_000L);

        String scrape = registry.scrape();
        assertTrue(scrape.contains("prizeflux_worker_events_total{outcome=\"processed\"} 1.0"));
        assertTrue(scrape.contains("prizeflux_worker_events_total{outcome=\"duplicate\"} 1.0"));
        assertTrue(scrape.contains("prizeflux_worker_fulfillment_executions_total{outcome=\"retry_scheduled\"} 1.0"));
        assertTrue(scrape.contains("prizeflux_worker_fulfillment_executions_total{outcome=\"manual_review\"} 1.0"));
        assertEquals(1L, registry.find("prizeflux.worker.consume").tag("outcome", "processed").timer().count());
        assertEquals(1L, registry.find("prizeflux.worker.fulfillment.execution")
                .tag("outcome", "retry_scheduled").timer().count());
        assertFalse(scrape.contains("eventId") || scrape.contains("drawId") || scrape.contains("userId"));
    }
}
