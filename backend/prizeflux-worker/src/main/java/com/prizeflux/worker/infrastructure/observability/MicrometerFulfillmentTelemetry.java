package com.prizeflux.worker.infrastructure.observability;

import com.prizeflux.worker.application.FulfillmentTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Micrometer adapter for bounded Kafka consumption and durable fulfillment execution outcomes. */
@Component
public final class MicrometerFulfillmentTelemetry implements FulfillmentTelemetry {
    private final MeterRegistry registry;

    public MicrometerFulfillmentTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordConsume(ConsumeOutcome outcome, long elapsedNanos) {
        String label = outcome.name().toLowerCase(Locale.ROOT);
        registry.counter("prizeflux.worker.events", "outcome", label).increment();
        Timer.builder("prizeflux.worker.consume")
                .tag("outcome", label)
                .register(registry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordExecution(ExecutionOutcome outcome, long elapsedNanos) {
        String label = outcome.name().toLowerCase(Locale.ROOT);
        registry.counter("prizeflux.worker.fulfillment.executions", "outcome", label).increment();
        Timer.builder("prizeflux.worker.fulfillment.execution")
                .tag("outcome", label)
                .register(registry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }
}
