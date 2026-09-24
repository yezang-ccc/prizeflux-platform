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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {
    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final DomainEventPublisher transport = mock(DomainEventPublisher.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void successfulBrokerAcknowledgementMarksLeaseSent() throws Exception {
        OutboxEvent event = event(1);
        when(outbox.claim(eq(10), anyString(), eq(NOW.plusSeconds(30)), eq(NOW)))
                .thenReturn(List.of(event));

        assertThat(relay().publishDue()).isEqualTo(1);

        verify(outbox).releaseExpired(NOW);
        verify(transport).publish(event);
        verify(outbox).markSent(eq(event.eventId()), anyString());
    }

    @Test
    void transportFailureReleasesOrDeadLettersAtAttemptLimit() throws Exception {
        OutboxEvent retry = event(1);
        OutboxEvent exhausted = new OutboxEvent("event-2", "PrizeFulfillmentRequested", 2, 1, "{}", 3);
        when(outbox.claim(eq(10), anyString(), eq(NOW.plusSeconds(30)), eq(NOW)))
                .thenReturn(List.of(retry, exhausted));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(transport).publish(org.mockito.ArgumentMatchers.any());

        assertThat(relay().publishDue()).isEqualTo(2);

        verify(outbox).release(eq(retry.eventId()), anyString());
        verify(outbox).markDead(eq(exhausted.eventId()), anyString());
    }

    @Test
    void rejectsInvalidRuntimeConfiguration() {
        assertThatThrownBy(() -> new OutboxPublisher(outbox, transport, clock, 0,
                Duration.ofSeconds(30), 3)).isInstanceOf(IllegalArgumentException.class);
    }

    private OutboxPublisher relay() {
        return new OutboxPublisher(outbox, transport, clock, 10, Duration.ofSeconds(30), 3);
    }

    private static OutboxEvent event(int attempts) {
        return new OutboxEvent("event-1", "PrizeFulfillmentRequested", 1, 1, "{}", attempts);
    }
}
