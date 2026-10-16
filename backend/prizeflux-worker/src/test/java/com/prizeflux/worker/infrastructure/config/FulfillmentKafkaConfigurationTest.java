package com.prizeflux.worker.infrastructure.config;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class FulfillmentKafkaConfigurationTest {
    @Test
    void databaseFailuresRemainUnacknowledgedBeyondDefaultRetryLimit() {
        var configurer = mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);
        doAnswer(invocation -> {
            invocation.<ConcurrentKafkaListenerContainerFactory<Object, Object>>getArgument(0)
                    .setConsumerFactory(consumerFactory);
            return null;
        }).when(configurer).configure(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        var factory = new FulfillmentKafkaConfiguration().kafkaListenerContainerFactory(
                configurer, consumerFactory);
        var container = factory.createContainer("prizeflux.fulfillment.requested");
        var handler = (DefaultErrorHandler) container.getCommonErrorHandler();
        assertThat(handler.isAckAfterHandle()).isFalse();
        assertThat(container.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(container.getContainerProperties().isSyncCommits()).isTrue();

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "prizeflux.fulfillment.requested", 0, 7L, "event-7", "payload");
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer listener = mock(MessageListenerContainer.class);
        RuntimeException failure = new IllegalStateException("worker database unavailable");
        for (int delivery = 0; delivery < 12; delivery++) {
            assertThat(handler.handleOne(failure, record, consumer, listener))
                    .as("delivery %s must remain eligible for redelivery", delivery + 1)
                    .isFalse();
        }
    }
}
