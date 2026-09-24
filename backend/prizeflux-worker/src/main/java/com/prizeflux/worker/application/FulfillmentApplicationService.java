package com.prizeflux.worker.application;

import com.prizeflux.contract.event.FulfillmentStatus;
import com.prizeflux.contract.event.PrizeFulfillmentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 消费用例：把"处理记录"与"内部虚拟权益账本"放在同一个 Worker 事务内提交，之后才确认消费。
 * 幂等由数据库唯一键保证——先写账本，命中唯一键（event_id 重复事件 / draw_id 同一中奖结果）即幂等跳过；
 * 处理标记在同一事务内随后写入，不会出现"记了处理却没落账"或反之的半边状态。
 */
@Service
public class FulfillmentApplicationService {
    private final FulfillmentRepository fulfillments;
    private final ProcessedEventRepository processedEvents;
    private final Clock clock;

    public FulfillmentApplicationService(FulfillmentRepository fulfillments,
            ProcessedEventRepository processedEvents, Clock clock) {
        this.fulfillments = fulfillments;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    @Transactional
    public ConsumeOutcome consume(PrizeFulfillmentEvent event, String payload) {
        Instant now = clock.instant();
        // 先落账：唯一键同时挡住"重复事件"与"同一中奖结果"，命中即幂等跳过。
        if (!fulfillments.create(event, FulfillmentStatus.PENDING, payload, now)) {
            return ConsumeOutcome.DUPLICATE;
        }
        // 再记处理标记（同一事务）。
        processedEvents.mark(event.eventId(), now);
        return ConsumeOutcome.PROCESSED;
    }
}
