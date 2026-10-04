package com.prizeflux.campaign.infrastructure.observability;

import com.prizeflux.campaign.application.DrawTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Micrometer adapter. Dimensions are fixed enums; business IDs never become metric labels. */
@Component
public final class MicrometerDrawTelemetry implements DrawTelemetry {
    private final MeterRegistry registry;

    public MicrometerDrawTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordTransaction(TransactionOutcome outcome, long elapsedNanos) {
        String label = label(outcome);
        registry.counter("prizeflux.draw.transactions", "outcome", label).increment();
        Timer.builder("prizeflux.draw.transaction")
                .tag("outcome", label)
                .register(registry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordDatabaseSegment(DatabaseSegment segment, SegmentOutcome outcome, long elapsedNanos) {
        String segmentLabel = label(segment);
        String outcomeLabel = label(outcome);
        registry.counter("prizeflux.draw.database.operations",
                "segment", segmentLabel, "outcome", outcomeLabel).increment();
        Timer.builder("prizeflux.draw.database.operation")
                .tag("segment", segmentLabel)
                .tag("outcome", outcomeLabel)
                .register(registry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
