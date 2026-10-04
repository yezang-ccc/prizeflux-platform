package com.prizeflux.campaign.infrastructure.observability;

import com.prizeflux.campaign.application.DrawQueueTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerDrawQueueTelemetryTest {
    @Test
    void recordsBoundedOutcomesAndSeparatesSubmissionQueryAndProcessingTimers() {
        var registry = new SimpleMeterRegistry();
        var telemetry = new MicrometerDrawQueueTelemetry(registry);

        telemetry.recordSubmission(DrawQueueTelemetry.SubmissionOutcome.ACCEPTED, 1_000_000L);
        telemetry.recordStatusQuery(DrawQueueTelemetry.QueryOutcome.FOUND, 2_000_000L);
        telemetry.recordProcessing(DrawQueueTelemetry.ProcessingOutcome.COMPLETED, 3_000_000L);

        assertThat(registry.get("prizeflux.draw.queue.submissions")
                .tag("outcome", "accepted").counter().count()).isEqualTo(1);
        assertThat(registry.get("prizeflux.draw.queue.status.queries")
                .tag("outcome", "found").counter().count()).isEqualTo(1);
        assertThat(registry.get("prizeflux.draw.queue.processing")
                .tag("outcome", "completed").counter().count()).isEqualTo(1);
        assertThat(registry.get("prizeflux.draw.queue.enqueue")
                .tag("outcome", "accepted").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1.0);
        assertThat(registry.get("prizeflux.draw.queue.status.query")
                .tag("outcome", "found").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2.0);
        assertThat(registry.get("prizeflux.draw.queue.process")
                .tag("outcome", "completed").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(3.0);
    }
}
