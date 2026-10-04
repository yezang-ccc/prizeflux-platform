package com.prizeflux.campaign.infrastructure.observability;

import com.prizeflux.campaign.application.OutboxTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Micrometer adapter for bounded Outbox Relay outcomes and acknowledgement latency. */
@Component
public final class MicrometerOutboxTelemetry implements OutboxTelemetry {
    private final MeterRegistry registry;

    public MicrometerOutboxTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordPublish(PublishOutcome outcome, long elapsedNanos, long createToConfirmNanos) {
        String label = outcome.name().toLowerCase(Locale.ROOT);
        registry.counter("prizeflux.outbox.publish.events", "outcome", label).increment();
        Timer.builder("prizeflux.outbox.publish")
                .tag("outcome", label)
                .register(registry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
        if (outcome == PublishOutcome.SENT && createToConfirmNanos >= 0) {
            registry.timer("prizeflux.outbox.create.to.confirm")
                    .record(createToConfirmNanos, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void recordLeaseReclaimed(long eventCount) {
        if (eventCount > 0) {
            registry.counter("prizeflux.outbox.leases.reclaimed").increment(eventCount);
        }
    }

    @Override
    public void recordDatabaseStage(DatabaseStage stage, StageOutcome outcome, long elapsedNanos) {
        registry.timer("prizeflux.outbox.database.stage",
                        "stage", stage.name().toLowerCase(Locale.ROOT),
                        "outcome", outcome.name().toLowerCase(Locale.ROOT))
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }
}
