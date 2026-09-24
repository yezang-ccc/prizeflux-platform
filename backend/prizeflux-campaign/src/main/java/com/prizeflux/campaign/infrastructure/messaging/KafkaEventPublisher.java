package com.prizeflux.campaign.infrastructure.messaging;

import com.prizeflux.campaign.application.DomainEventPublisher;
import com.prizeflux.campaign.application.OutboxEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 用 Kafka 实现 {@link DomainEventPublisher}：以 eventId 为消息键、payload 为消息体同步投递。
 * 只有等待 Broker 确认成功后才返回，这样 {@code OutboxPublisher} 才会把记录置为 SENT；
 * 投递异常会向上抛出，由 Relay 决定重试或置为 DEAD。
 * 仅在 prizeflux.kafka.enabled=true 时装配；未启用（轻量模式/纯本地）不会创建任何 Kafka 连接。
 */
@Component
@ConditionalOnProperty(name = "prizeflux.kafka.enabled", havingValue = "true")
public class KafkaEventPublisher implements DomainEventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final Duration sendTimeout;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka,
            @Value("${prizeflux.kafka.topic-fulfillment:prizeflux.fulfillment.requested}") String topic,
            @Value("${prizeflux.kafka.send-timeout:PT10S}") Duration sendTimeout) {
        this.kafka = kafka;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void publish(OutboxEvent event) throws Exception {
        // eventId 作为分区键：同一事件落到同一分区，Worker 据此按 eventId 幂等去重（D7-04）。
        kafka.send(topic, event.eventId(), event.payload())
                .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
