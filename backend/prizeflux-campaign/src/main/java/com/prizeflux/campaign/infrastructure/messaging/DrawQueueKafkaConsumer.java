package com.prizeflux.campaign.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.application.DrawQueueApplicationService;
import com.prizeflux.campaign.application.DrawQueueNotification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka payload is only a wake-up hint; all trusted request data is loaded from MySQL. */
@Component
@ConditionalOnProperty(name = {"prizeflux.kafka.enabled", "prizeflux.kafka.draw-queue-enabled"},
        havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "prizeflux.kafka.draw-queue-consumer-enabled", havingValue = "true")
public final class DrawQueueKafkaConsumer {
    private final DrawQueueApplicationService queue;
    private final ObjectMapper json;

    public DrawQueueKafkaConsumer(DrawQueueApplicationService queue, ObjectMapper json) {
        this.queue = queue;
        this.json = json;
    }

    @KafkaListener(topics = "${prizeflux.kafka.topic-draw-queue:prizeflux.draw.queued}",
            groupId = "${prizeflux.kafka.group-draw-queue:prizeflux-campaign-draw-queue}",
            concurrency = "${prizeflux.kafka.draw-queue-concurrency:1}",
            containerFactory = "drawQueueKafkaListenerContainerFactory")
    public void consume(String payload) throws Exception {
        DrawQueueNotification notification = json.readValue(payload, DrawQueueNotification.class);
        if (notification == null || notification.queueRequestId() <= 0) {
            throw new IllegalArgumentException("Invalid draw queue notification");
        }
        queue.process(notification.queueRequestId());
    }
}
