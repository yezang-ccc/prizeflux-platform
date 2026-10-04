package com.prizeflux.campaign.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {
    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final DomainEventPublisher transport = mock(DomainEventPublisher.class);
    private final OutboxTelemetry telemetry = mock(OutboxTelemetry.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void successfulBrokerAcknowledgementMarksLeaseSent() throws Exception {
        OutboxEvent event = new OutboxEvent("event-1", "PrizeFulfillmentRequested", 1, 1, "{}", 1,
                NOW.minusSeconds(4));
        when(outbox.claim(eq(10), anyString(), eq(NOW.plusSeconds(30)), eq(NOW)))
                .thenReturn(List.of(event));
        when(outbox.markSent(eq(event.eventId()), anyString())).thenReturn(true);

        assertThat(relay().publishDue()).isEqualTo(1);

        verify(outbox).releaseExpired(NOW);
        verify(transport).publish(event);
        verify(outbox).markSent(eq(event.eventId()), anyString());
        verify(telemetry).recordDatabaseStage(eq(OutboxTelemetry.DatabaseStage.RECLAIM),
                eq(OutboxTelemetry.StageOutcome.SUCCESS), anyLong());
        verify(telemetry).recordDatabaseStage(eq(OutboxTelemetry.DatabaseStage.CLAIM),
                eq(OutboxTelemetry.StageOutcome.SUCCESS), anyLong());
        verify(telemetry).recordDatabaseStage(eq(OutboxTelemetry.DatabaseStage.MARK_SENT),
                eq(OutboxTelemetry.StageOutcome.SUCCESS), anyLong());
        verify(telemetry).recordPublish(eq(OutboxTelemetry.PublishOutcome.SENT), anyLong(),
                eq(Duration.ofSeconds(4).toNanos()));
    }

    @Test
    void transportFailureReleasesOrDeadLettersAtAttemptLimit() throws Exception {
        OutboxEvent retry = event(1);
        OutboxEvent exhausted = new OutboxEvent("event-2", "PrizeFulfillmentRequested", 2, 1, "{}", 3);
        when(outbox.claim(eq(10), anyString(), eq(NOW.plusSeconds(30)), eq(NOW)))
                .thenReturn(List.of(retry, exhausted));
        when(outbox.release(eq(retry.eventId()), anyString(), eq(NOW.plusSeconds(2)))).thenReturn(true);
        when(outbox.markDead(eq(exhausted.eventId()), anyString())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(transport).publish(org.mockito.ArgumentMatchers.any());

        assertThat(relay().publishDue()).isEqualTo(2);

        verify(outbox).release(eq(retry.eventId()), anyString(), eq(NOW.plusSeconds(2)));
        verify(outbox).markDead(eq(exhausted.eventId()), anyString());
        verify(telemetry).recordDatabaseStage(eq(OutboxTelemetry.DatabaseStage.RELEASE_RETRY),
                eq(OutboxTelemetry.StageOutcome.SUCCESS), anyLong());
        verify(telemetry).recordDatabaseStage(eq(OutboxTelemetry.DatabaseStage.MARK_DEAD),
                eq(OutboxTelemetry.StageOutcome.SUCCESS), anyLong());
    }

    @Test
    void rejectsInvalidRuntimeConfiguration() {
        assertThatThrownBy(() -> new OutboxPublisher(outbox, transport, telemetry, clock, 0,
                Duration.ofSeconds(30), 3, Duration.ofSeconds(2), Duration.ofSeconds(30),
                new BoundedOutboxDispatcher(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxPublisher(outbox, transport, telemetry, clock, 10,
                Duration.ofSeconds(30), 3, Duration.ZERO, Duration.ofSeconds(30),
                new BoundedOutboxDispatcher(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxPublisher(outbox, transport, telemetry, clock, 10,
                Duration.ofSeconds(30), 3, Duration.ofSeconds(4), Duration.ofSeconds(2),
                new BoundedOutboxDispatcher(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void interruptedSendStopsBatchAndLeavesUnsentLeasesForRecovery() throws Exception {
        var first = event(1);
        var second = new OutboxEvent("event-2", "DrawQueued", 2, 1, "{}", 1);
        when(outbox.claim(eq(10), anyString(), eq(NOW.plusSeconds(30)), eq(NOW)))
                .thenReturn(List.of(first, second));
        when(outbox.release(eq(first.eventId()), anyString(), eq(NOW.plusSeconds(2)))).thenReturn(true);
        org.mockito.Mockito.doThrow(new InterruptedException()).when(transport).publish(first);
        try {
            assertThat(relay().publishDue()).isEqualTo(2); // Number claimed, not number sent.
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(transport, org.mockito.Mockito.never()).publish(second);
            verify(outbox, org.mockito.Mockito.never()).release(eq(second.eventId()), anyString(),
                    org.mockito.ArgumentMatchers.any());
            verify(telemetry).recordPublish(eq(OutboxTelemetry.PublishOutcome.RETRY_SCHEDULED),
                    anyLong(), eq(-1L));
        } finally {
            Thread.interrupted();
        }
    }

    private OutboxPublisher relay() {
        return new OutboxPublisher(outbox, transport, telemetry, clock, 10, Duration.ofSeconds(30), 3,
                Duration.ofSeconds(2), Duration.ofSeconds(30), new BoundedOutboxDispatcher(1));
    }

    @Test
    void parallelModeRejectsLeaseShorterThanItsRecoveryMargin() {
        var dispatcher = new BoundedOutboxDispatcher(2);
        try {
            assertThatThrownBy(() -> new OutboxPublisher(outbox, transport, telemetry, clock,
                    50, Duration.ofSeconds(20), 3, Duration.ofSeconds(2), Duration.ofSeconds(30),
                    dispatcher)).isInstanceOf(IllegalArgumentException.class);
        } finally { dispatcher.close(); }
    }

    @Test
    void retryAvailabilityUsesBoundedExponentialBackoff() throws Exception {
        var firstAttempt = event(1);
        var secondAttempt = event(2);
        var thirdAttempt = new OutboxEvent("event-3", "PrizeFulfillmentRequested", 3, 1, "{}", 3);
        when(outbox.claim(eq(10), anyString(), eq(NOW.plusSeconds(30)), eq(NOW)))
                .thenReturn(List.of(firstAttempt, secondAttempt, thirdAttempt));
        when(outbox.release(eq(firstAttempt.eventId()), anyString(), eq(NOW.plusSeconds(2))))
                .thenReturn(true);
        when(outbox.release(eq(secondAttempt.eventId()), anyString(), eq(NOW.plusSeconds(4))))
                .thenReturn(true);
        when(outbox.release(eq(thirdAttempt.eventId()), anyString(), eq(NOW.plusSeconds(5))))
                .thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(transport).publish(org.mockito.ArgumentMatchers.any());

        var boundedRelay = new OutboxPublisher(outbox, transport, telemetry, clock, 10,
                Duration.ofSeconds(30), 6, Duration.ofSeconds(2), Duration.ofSeconds(5),
                new BoundedOutboxDispatcher(1));
        assertThat(boundedRelay.publishDue()).isEqualTo(3);

        verify(outbox).release(eq(firstAttempt.eventId()), anyString(), eq(NOW.plusSeconds(2)));
        verify(outbox).release(eq(secondAttempt.eventId()), anyString(), eq(NOW.plusSeconds(4)));
        verify(outbox).release(eq(thirdAttempt.eventId()), anyString(), eq(NOW.plusSeconds(5)));
    }

    @Test
    void databaseMetricFailureCannotChangeConfirmedPublishOrReclaim() throws Exception {
        var event = event(1);
        when(outbox.claim(eq(10), anyString(), eq(NOW.plusSeconds(30)), eq(NOW)))
                .thenReturn(List.of(event));
        when(outbox.markSent(eq(event.eventId()), anyString())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("metrics unavailable"))
                .when(telemetry).recordDatabaseStage(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), anyLong());
        assertThat(relay().publishDue()).isEqualTo(1);
        verify(outbox).markSent(eq(event.eventId()), anyString());
        verify(outbox, org.mockito.Mockito.never()).release(eq(event.eventId()), anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(telemetry).recordPublish(eq(OutboxTelemetry.PublishOutcome.SENT), anyLong(), anyLong());
    }

    @Test
    void reclaimFailureIsMeasuredAndOriginalExceptionSurvivesMetricFailure() {
        var original = new IllegalStateException("database unavailable");
        when(outbox.releaseExpired(NOW)).thenThrow(original);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("metrics unavailable"))
                .when(telemetry).recordDatabaseStage(eq(OutboxTelemetry.DatabaseStage.RECLAIM),
                        eq(OutboxTelemetry.StageOutcome.FAILURE), anyLong());
        assertThatThrownBy(() -> relay().publishDue()).isSameAs(original);
        verify(telemetry).recordDatabaseStage(eq(OutboxTelemetry.DatabaseStage.RECLAIM),
                eq(OutboxTelemetry.StageOutcome.FAILURE), anyLong());
        verify(outbox, org.mockito.Mockito.never()).claim(eq(10), anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static OutboxEvent event(int attempts) {
        return new OutboxEvent("event-1", "PrizeFulfillmentRequested", 1, 1, "{}", attempts);
    }
}
