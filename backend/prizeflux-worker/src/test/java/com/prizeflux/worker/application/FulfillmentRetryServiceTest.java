package com.prizeflux.worker.application;

import com.prizeflux.contract.event.FulfillmentStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FulfillmentRetryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    private final FulfillmentRepository repository = mock(FulfillmentRepository.class);
    private final FulfillmentExecutor executor = mock(FulfillmentExecutor.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FulfillmentTelemetry telemetry = new com.prizeflux.worker.infrastructure.observability
            .MicrometerFulfillmentTelemetry(registry);
    private final FulfillmentRetryService service = new FulfillmentRetryService(repository, executor,
            new FulfillmentRetryPolicy(), Clock.fixed(NOW, ZoneOffset.UTC), telemetry);

    @Test
    void successfulExecutionRecordsSucceededOutcome() throws Exception {
        var claim = claim(1);
        when(repository.claimDue(org.mockito.ArgumentMatchers.eq(NOW), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(claim));
        when(repository.markSucceeded(claim.id(), claim.leaseToken(), NOW)).thenReturn(true);

        service.processDue();

        verify(executor).execute("1005", "payload");
        assertOutcome(FulfillmentTelemetry.ExecutionOutcome.SUCCEEDED);
    }

    @Test
    void transientFailureRecordsRetryScheduledOutcome() throws Exception {
        var claim = claim(1);
        when(repository.claimDue(org.mockito.ArgumentMatchers.eq(NOW), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(claim));
        org.mockito.Mockito.doThrow(new FulfillmentExecutionException("PROVIDER_BUSY", false))
                .when(executor).execute("1005", "payload");
        when(repository.scheduleRetry(claim.id(), claim.leaseToken(), NOW.plusSeconds(1),
                "PROVIDER_BUSY", NOW)).thenReturn(true);

        service.processDue();

        verify(repository).scheduleRetry(claim.id(), claim.leaseToken(), NOW.plusSeconds(1),
                "PROVIDER_BUSY", NOW);
        assertOutcome(FulfillmentTelemetry.ExecutionOutcome.RETRY_SCHEDULED);
    }

    @Test
    void permanentFailureRecordsManualReviewOutcome() throws Exception {
        var claim = claim(1);
        when(repository.claimDue(org.mockito.ArgumentMatchers.eq(NOW), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(claim));
        org.mockito.Mockito.doThrow(new FulfillmentExecutionException("INVALID_RECIPIENT", true))
                .when(executor).execute("1005", "payload");
        when(repository.moveToManualReview(claim.id(), claim.drawId(), claim.leaseToken(),
                "INVALID_RECIPIENT", NOW)).thenReturn(true);

        service.processDue();

        verify(repository).moveToManualReview(claim.id(), claim.drawId(), claim.leaseToken(),
                "INVALID_RECIPIENT", NOW);
        assertOutcome(FulfillmentTelemetry.ExecutionOutcome.MANUAL_REVIEW);
    }

    private FulfillmentClaim claim(int attempts) {
        return new FulfillmentClaim(7L, 1005L, "event-7", "payload", attempts, "lease-7");
    }

    private void assertOutcome(FulfillmentTelemetry.ExecutionOutcome outcome) {
        assertEquals(1.0, registry.counter("prizeflux.worker.fulfillment.executions",
                "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT)).count());
    }
}
