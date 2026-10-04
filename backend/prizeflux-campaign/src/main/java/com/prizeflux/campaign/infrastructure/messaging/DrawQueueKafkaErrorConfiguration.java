package com.prizeflux.campaign.infrastructure.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/** Poison queue notifications receive bounded broker retries and then a correlated DLT record. */
@Configuration
@ConditionalOnProperty(name = {"prizeflux.kafka.enabled", "prizeflux.kafka.draw-queue-enabled",
        "prizeflux.kafka.draw-queue-consumer-enabled"}, havingValue = "true")
public class DrawQueueKafkaErrorConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> drawQueueKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory, CommonErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        return factory;
    }

    @Bean
    CommonErrorHandler drawQueueKafkaErrorHandler(KafkaTemplate<String, String> kafka,
            @Value("${prizeflux.kafka.topic-draw-queue-dlt:prizeflux.draw.queued.dlt}") String dltTopic,
            @Value("${prizeflux.kafka.draw-queue-kafka-retry-delay-ms:500}") long retryDelayMillis,
            @Value("${prizeflux.kafka.draw-queue-kafka-retries:2}") long retries) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, failure) -> new TopicPartition(dltTopic, -1));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        return new DefaultErrorHandler(recoverer,
                new FixedBackOff(retryDelayMillis, retries));
    }

    /** Development default is one broker replica; production must override this to match its cluster. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "prizeflux.kafka.manage-topics", havingValue = "true")
    NewTopic drawQueueDeadLetterTopic(
            @Value("${prizeflux.kafka.topic-draw-queue-dlt:prizeflux.draw.queued.dlt}") String dltTopic,
            @Value("${prizeflux.kafka.draw-queue-dlt-replication-factor:1}") short replicationFactor) {
        if (replicationFactor < 1) throw new IllegalArgumentException("DLT replication factor must be positive");
        return new NewTopic(dltTopic, 1, replicationFactor);
    }
}
