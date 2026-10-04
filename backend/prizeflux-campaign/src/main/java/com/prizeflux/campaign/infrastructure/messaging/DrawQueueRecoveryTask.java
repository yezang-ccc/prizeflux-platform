package com.prizeflux.campaign.infrastructure.messaging;

import com.prizeflux.campaign.application.DrawQueueApplicationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reclaims due or expired MySQL tasks even when the corresponding Kafka notification is missing. */
@Component
@ConditionalOnProperty(name = {"prizeflux.kafka.enabled", "prizeflux.kafka.draw-queue-enabled",
        "prizeflux.kafka.draw-queue-consumer-enabled"}, havingValue = "true")
public class DrawQueueRecoveryTask {
    private final DrawQueueApplicationService queue;

    public DrawQueueRecoveryTask(DrawQueueApplicationService queue) {
        this.queue = queue;
    }

    @Scheduled(initialDelayString = "${prizeflux.kafka.draw-queue-scan-initial-delay-ms:1000}",
            fixedDelayString = "${prizeflux.kafka.draw-queue-scan-delay-ms:1000}")
    public void recoverDueRequests() {
        queue.processDue();
    }
}
