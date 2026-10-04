package com.prizeflux.campaign.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.application.DrawQueueNotification;
import com.prizeflux.campaign.application.DomainEventPublisher;
import com.prizeflux.campaign.application.OutboxEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用 Kafka 实现 {@link DomainEventPublisher}：以 eventId 为消息键、payload 为消息体同步投递。
 * 只有等待 Broker 确认成功后才返回，这样 {@code OutboxPublisher} 才会把记录置为 SENT；
 * 投递异常会向上抛出，由 Relay 决定重试或置为 DEAD。
 * 仅在 prizeflux.kafka.enabled=true 时装配；未启用（轻量模式/纯本地）不会创建任何 Kafka 连接。
 */
@Component
@ConditionalOnProperty(name = "prizeflux.kafka.enabled", havingValue = "true")
public class KafkaEventPublisher implements DomainEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String fulfillmentTopic;
    private final String drawQueueTopic;
    private final Duration sendTimeout;
    private final MeterRegistry registry;
    private final AtomicBoolean telemetryFailureLogged = new AtomicBoolean();

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json,
            @Value("${prizeflux.kafka.topic-fulfillment:prizeflux.fulfillment.requested}") String fulfillmentTopic,
            @Value("${prizeflux.kafka.topic-draw-queue:prizeflux.draw.queued}") String drawQueueTopic,
            @Value("${prizeflux.kafka.send-timeout:PT10S}") Duration sendTimeout,
            MeterRegistry registry) {
        this.kafka = kafka;
        this.json = json;
        this.fulfillmentTopic = fulfillmentTopic;
        this.drawQueueTopic = drawQueueTopic;
        this.sendTimeout = sendTimeout;
        this.registry = registry;
    }

    @Override
    public void publish(OutboxEvent event) throws Exception {
        boolean drawQueue = "DrawQueued".equals(event.eventType());
        String topic = drawQueue ? drawQueueTopic : fulfillmentTopic;
        // 不用 campaignId 作 key，避免单个热门活动被固定到一个分区。
        // 通知只允许携带稳定队列 ID；业务请求事实仍由 Consumer 从 MySQL 读取。
        String key = drawQueue
                ? Long.toString(queueRequestId(event.payload()))
                : event.eventId();
        String kind = drawQueue ? "draw_queue" : "fulfillment";
        CompletableFuture<SendResult<String, String>> future;
        long started = System.nanoTime();
        boolean success = false;
        try {
            future = kafka.send(topic, key, event.payload());
            success = true;
        } finally {
            recordStage(kind, "enqueue", success, System.nanoTime() - started);
        }
        started = System.nanoTime();
        success = false;
        try {
            future.get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            success = true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            recordStage(kind, "broker_ack_wait", success, System.nanoTime() - started);
        }
    }

    private void recordStage(String kind, String stage, boolean success, long elapsedNanos) {
        try {
            registry.timer("prizeflux.kafka.publish.stage", "kind", kind, "stage", stage,
                    "outcome", success ? "success" : "failure")
                    .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException failure) {
            // A failed metric must not turn a broker acknowledgement into an Outbox retry.
            if (telemetryFailureLogged.compareAndSet(false, true)) {
                log.warn("kafka_publish_telemetry_failed failureType={}", failure.getClass().getSimpleName());
            }
        }
    }

    private long queueRequestId(String payload) throws Exception {
        DrawQueueNotification notification = json.readValue(payload, DrawQueueNotification.class);
        if (notification == null || notification.queueRequestId() <= 0) {
            throw new IllegalArgumentException("Invalid draw queue outbox payload");
        }
        return notification.queueRequestId();
    }
}
