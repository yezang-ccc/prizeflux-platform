package com.prizeflux.campaign.infrastructure.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provision the queue topic with enough partitions for a hot campaign to spread across executors. */
@Configuration
@ConditionalOnProperty(name = {"prizeflux.kafka.enabled", "prizeflux.kafka.draw-queue-enabled",
        "prizeflux.kafka.manage-topics"}, havingValue = "true")
public class DrawQueueKafkaTopicConfiguration {
    @Bean
    NewTopic drawQueueTopic(
            @Value("${prizeflux.kafka.topic-draw-queue:prizeflux.draw.queued}") String topic,
            @Value("${prizeflux.kafka.draw-queue-partitions:12}") int partitions,
            @Value("${prizeflux.kafka.draw-queue-replication-factor:1}") short replicationFactor) {
        if (partitions < 1 || replicationFactor < 1) {
            throw new IllegalArgumentException("Invalid draw queue Kafka topic configuration");
        }
        return new NewTopic(topic, partitions, replicationFactor);
    }
}
