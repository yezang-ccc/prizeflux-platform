package com.prizeflux.worker.application;

import com.prizeflux.contract.event.FulfillmentStatus;
import com.prizeflux.contract.event.PrizeFulfillmentEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FulfillmentApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
    private final FulfillmentRepository fulfillments = mock(FulfillmentRepository.class);
    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final FulfillmentApplicationService service = new FulfillmentApplicationService(
            fulfillments, processedEvents, Clock.fixed(NOW, ZoneOffset.UTC));
    private final PrizeFulfillmentEvent event = new PrizeFulfillmentEvent(
            1, "event-1", 10, 20, 1, 30, 40, NOW);

    @Test
    void newEventWritesLedgerBeforeMarker() {
        when(fulfillments.create(event, FulfillmentStatus.PENDING, "{}", NOW)).thenReturn(true);

        assertThat(service.consume(event, "{}")).isEqualTo(ConsumeOutcome.PROCESSED);

        var order = org.mockito.Mockito.inOrder(fulfillments, processedEvents);
        order.verify(fulfillments).create(event, FulfillmentStatus.PENDING, "{}", NOW);
        order.verify(processedEvents).mark("event-1", NOW);
    }

    @Test
    void duplicateLedgerDoesNotWriteMarkerAgain() {
        when(fulfillments.create(event, FulfillmentStatus.PENDING, "{}", NOW)).thenReturn(false);

        assertThat(service.consume(event, "{}")).isEqualTo(ConsumeOutcome.DUPLICATE);

        verify(processedEvents, never()).mark(eq("event-1"), eq(NOW));
    }
}
