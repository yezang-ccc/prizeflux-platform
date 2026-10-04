package com.prizeflux.campaign.infrastructure.observability;

import com.prizeflux.campaign.application.DrawQueueTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Micrometer adapter. Its only tags come from closed enums, so identifiers cannot create high-cardinality series. */
@Component
public final class MicrometerDrawQueueTelemetry implements DrawQueueTelemetry {
    private final MeterRegistry registry;

    public MicrometerDrawQueueTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordSubmission(SubmissionOutcome outcome, long elapsedNanos) {
        record("prizeflux.draw.queue.submissions", "prizeflux.draw.queue.enqueue", outcome, elapsedNanos);
    }

    @Override
    public void recordStatusQuery(QueryOutcome outcome, long elapsedNanos) {
        record("prizeflux.draw.queue.status.queries", "prizeflux.draw.queue.status.query", outcome,
                elapsedNanos);
    }

    @Override
    public void recordProcessing(ProcessingOutcome outcome, long elapsedNanos) {
        record("prizeflux.draw.queue.processing", "prizeflux.draw.queue.process", outcome, elapsedNanos);
    }

    private void record(String counterName, String timerName, Enum<?> outcome, long elapsedNanos) {
        String label = outcome.name().toLowerCase(Locale.ROOT);
        registry.counter(counterName, "outcome", label).increment();
        Timer.builder(timerName)
                .tag("outcome", label)
                .register(registry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }
}
