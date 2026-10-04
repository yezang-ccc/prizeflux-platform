package com.prizeflux.campaign.infrastructure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/** Only the Campaign Kafka runtime enables this separate draw-queue consumer. */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = {"prizeflux.kafka.enabled", "prizeflux.kafka.draw-queue-enabled"},
        havingValue = "true")
public class DrawQueueKafkaConfiguration {
}
