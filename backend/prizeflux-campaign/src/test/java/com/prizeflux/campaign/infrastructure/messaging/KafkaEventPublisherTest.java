package com.prizeflux.campaign.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.application.OutboxEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaEventPublisherTest {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private KafkaEventPublisher publisher(MeterRegistry meters) {
        return new KafkaEventPublisher(kafka, new ObjectMapper(), "fulfillment", "queue",
                Duration.ofMillis(1), meters);
    }

    private OutboxEvent queueEvent() {
        return new OutboxEvent("event-1", "DrawQueued", 55, 1, "{\"queueRequestId\":99}", 1);
    }

    @Test
    void recordsSeparateEnqueueAndAcknowledgementStagesWithoutChangingQueueKey() throws Exception {
        when(kafka.send("queue", "99", queueEvent().payload()))
                .thenReturn(CompletableFuture.completedFuture(null));
        publisher(registry).publish(queueEvent());
        verify(kafka).send("queue", "99", queueEvent().payload());
        assertCount("enqueue", "success", 1);
        assertCount("broker_ack_wait", "success", 1);
    }

    @Test
    void asynchronousFailureAndTimeoutRemainFailuresForOutboxRetry() {
        when(kafka.send("queue", "99", queueEvent().payload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .thenReturn(new CompletableFuture<>());
        var publisher = publisher(registry);
        assertThatThrownBy(() -> publisher.publish(queueEvent())).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> publisher.publish(queueEvent())).isInstanceOf(TimeoutException.class);
        assertCount("enqueue", "success", 2);
        assertCount("broker_ack_wait", "failure", 2);
    }

    @Test
    void synchronousSendFailureDoesNotPretendToWaitForAcknowledgement() {
        when(kafka.send("queue", "99", queueEvent().payload()))
                .thenThrow(new IllegalStateException("metadata unavailable"));
        assertThatThrownBy(() -> publisher(registry).publish(queueEvent()))
                .isInstanceOf(IllegalStateException.class);
        assertCount("enqueue", "failure", 1);
        assertThat(registry.find("prizeflux.kafka.publish.stage").tag("stage", "broker_ack_wait").timer())
                .isNull();
    }

    @Test
    void metricsFailureCannotChangeAcknowledgedPublication() throws Exception {
        var brokenRegistry = mock(MeterRegistry.class);
        when(brokenRegistry.timer(anyString(), any(String[].class)))
                .thenThrow(new IllegalStateException("metrics unavailable"));
        when(kafka.send("queue", "99", queueEvent().payload()))
                .thenReturn(CompletableFuture.completedFuture(null));
        assertThatCode(() -> publisher(brokenRegistry).publish(queueEvent())).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptedAcknowledgementRestoresThreadInterrupt() throws Exception {
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new InterruptedException());
        when(kafka.send("queue", "99", queueEvent().payload())).thenReturn(future);
        try {
            assertThatThrownBy(() -> publisher(registry).publish(queueEvent()))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertCount("broker_ack_wait", "failure", 1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void fulfillmentRetainsEventIdKeyAndUsesBoundedKindLabel() throws Exception {
        var event = new OutboxEvent("win-1", "PrizeFulfillmentRequested", 55, 1, "{}", 1);
        when(kafka.send("fulfillment", "win-1", "{}"))
                .thenReturn(CompletableFuture.completedFuture(null));
        publisher(registry).publish(event);
        verify(kafka).send("fulfillment", "win-1", "{}");
        assertThat(registry.get("prizeflux.kafka.publish.stage").tags("kind", "fulfillment",
                "stage", "broker_ack_wait", "outcome", "success").timer().count()).isEqualTo(1);
    }

    private void assertCount(String stage, String outcome, long count) {
        assertThat(registry.get("prizeflux.kafka.publish.stage").tags("kind", "draw_queue",
                "stage", stage, "outcome", outcome).timer().count()).isEqualTo(count);
    }
}
