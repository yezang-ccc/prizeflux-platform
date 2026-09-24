package com.prizeflux.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.contract.event.PrizeFulfillmentEvent;
import com.prizeflux.worker.application.ConsumeOutcome;
import com.prizeflux.worker.application.FulfillmentApplicationService;
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

    public FulfillmentConsumer(ObjectMapper objectMapper, FulfillmentApplicationService service) {
        this.objectMapper = objectMapper;
        this.service = service;
    }

    @KafkaListener(
            topics = "${prizeflux.kafka.topic-fulfillment:prizeflux.fulfillment.requested}",
            groupId = "${prizeflux.kafka.group-id:prizeflux-worker}")
    public void onFulfillment(ConsumerRecord<String, String> record) {
        String eventId = record.key();
        String payload = record.value();
        PrizeFulfillmentEvent event;
        try {
            event = objectMapper.readValue(payload, PrizeFulfillmentEvent.class);
        } catch (JsonProcessingException e) {
            // 载荷非法：不可重试错误；D7-05 将路由到死信，这里先记日志并抛出触发重投。
            // 毒消息会重试；这里不记录原始消息键/载荷或反复打印堆栈，避免日志放大与泄漏。
            log.warn("malformed fulfillment payload type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("Malformed fulfillment event payload", e);
        }
        if (eventId == null || !eventId.equals(event.eventId())) {
            throw new IllegalStateException("Kafka key does not match fulfillment event ID");
        }
        ConsumeOutcome outcome = service.consume(event, payload);
        if (outcome == ConsumeOutcome.PROCESSED) {
            log.info("fulfillment processed eventId={} drawId={}", eventId, event.drawId());
        } else {
            log.info("fulfillment duplicate skipped eventId={} drawId={}", eventId, event.drawId());
        }
    }
}
