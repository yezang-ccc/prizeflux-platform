package com.prizeflux.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.contract.event.PrizeFulfillmentEvent;
import com.prizeflux.worker.application.ConsumeOutcome;
import com.prizeflux.worker.application.FulfillmentApplicationService;
import com.prizeflux.worker.infrastructure.observability.MicrometerFulfillmentTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FulfillmentConsumerTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final FulfillmentApplicationService service = mock(FulfillmentApplicationService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FulfillmentConsumer consumer = new FulfillmentConsumer(json, service,
            new MicrometerFulfillmentTelemetry(registry));
    private final PrizeFulfillmentEvent event = new PrizeFulfillmentEvent(
            1, "event-1", 10, 20, 1, 30, 40, Instant.parse("2026-09-24T00:00:00Z"));

    @Test
    void validRecordIsDecodedAndPassedToUseCase() throws Exception {
        String payload = json.writeValueAsString(event);
        when(service.consume(any(), anyString())).thenReturn(ConsumeOutcome.PROCESSED);

        consumer.onFulfillment(record("event-1", payload));

        verify(service).consume(event, payload);
        org.assertj.core.api.Assertions.assertThat(registry.get("prizeflux.worker.events")
                .tag("outcome", "processed").counter().count()).isEqualTo(1);
    }

    @Test
    void keyMismatchCannotBeRecordedAsAnotherEvent() throws Exception {
        assertThatThrownBy(() -> consumer.onFulfillment(
                record("different-event", json.writeValueAsString(event))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Kafka key does not match fulfillment event ID");
        verifyNoInteractions(service);
    }

    @Test
    void malformedPayloadDoesNotReachUseCase() {
        assertThatThrownBy(() -> consumer.onFulfillment(record("event-1", "not-json")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(service);
        org.assertj.core.api.Assertions.assertThat(registry.get("prizeflux.worker.events")
                .tag("outcome", "malformed_payload").counter().count()).isEqualTo(1);
    }

    private static ConsumerRecord<String, String> record(String key, String payload) {
        return new ConsumerRecord<>("prizeflux.fulfillment.requested", 0, 0L, key, payload);
    }
}
