package com.prizeflux.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.contract.event.PrizeFulfillmentEvent;
import com.prizeflux.worker.application.ConsumeOutcome;
import com.prizeflux.worker.application.FulfillmentApplicationService;
import com.prizeflux.worker.application.FulfillmentTelemetry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 发奖事件消费者：反序列化载荷 → 交用例幂等落账 → 方法正常返回后由容器确认消费。
 * 消费失败抛异常让 Kafka 至少一次重投；重复事件在用例层幂等跳过（D7-04）。
 */
@Component
public class FulfillmentConsumer {
    private static final Logger log = LoggerFactory.getLogger(FulfillmentConsumer.class);

    private final ObjectMapper objectMapper;
    private final FulfillmentApplicationService service;
    private final FulfillmentTelemetry telemetry;

    public FulfillmentConsumer(ObjectMapper objectMapper, FulfillmentApplicationService service,
            FulfillmentTelemetry telemetry) {
        this.objectMapper = objectMapper;
        this.service = service;
        this.telemetry = telemetry;
    }

    @KafkaListener(
            topics = "${prizeflux.kafka.topic-fulfillment:prizeflux.fulfillment.requested}",
            groupId = "${prizeflux.kafka.group-id:prizeflux-worker}")
    public void onFulfillment(ConsumerRecord<String, String> record) {
        long startedAt = System.nanoTime();
        String eventId = record.key();
        String payload = record.value();
        PrizeFulfillmentEvent event;
        try {
            event = objectMapper.readValue(payload, PrizeFulfillmentEvent.class);
        } catch (JsonProcessingException e) {
            // 载荷非法：不可重试错误；D7-05 将路由到死信，这里先记日志并抛出触发重投。
            // 毒消息会重试；这里不记录原始消息键/载荷或反复打印堆栈，避免日志放大与泄漏。
            log.warn("malformed fulfillment payload type={}", e.getClass().getSimpleName());
            recordTelemetry(FulfillmentTelemetry.ConsumeOutcome.MALFORMED_PAYLOAD, startedAt);
            throw new IllegalStateException("Malformed fulfillment event payload", e);
        }
        if (eventId == null || !eventId.equals(event.eventId())) {
            recordTelemetry(FulfillmentTelemetry.ConsumeOutcome.INVALID_EVENT_KEY, startedAt);
            throw new IllegalStateException("Kafka key does not match fulfillment event ID");
        }
        ConsumeOutcome outcome;
        try {
            outcome = service.consume(event, payload);
        } catch (RuntimeException failure) {
            recordTelemetry(FulfillmentTelemetry.ConsumeOutcome.PROCESSING_FAILED, startedAt);
            throw failure;
        }
        recordTelemetry(outcome == ConsumeOutcome.PROCESSED
                ? FulfillmentTelemetry.ConsumeOutcome.PROCESSED
                : FulfillmentTelemetry.ConsumeOutcome.DUPLICATE, startedAt);
        if (outcome == ConsumeOutcome.PROCESSED) {
            log.info("fulfillment processed eventId={} drawId={}", eventId, event.drawId());
        } else {
            log.info("fulfillment duplicate skipped eventId={} drawId={}", eventId, event.drawId());
        }
    }

    private void recordTelemetry(FulfillmentTelemetry.ConsumeOutcome outcome, long startedAt) {
        try {
            telemetry.recordConsume(outcome, Math.max(0L, System.nanoTime() - startedAt));
        } catch (RuntimeException failure) {
            log.warn("worker_consume_telemetry_failed failureType={}", failure.getClass().getSimpleName());
        }
    }
}
