package com.prizeflux.worker.application;

import java.time.Instant;

/**
 * 处理记录端口：以 eventId 持久标记事件已消费（传输层幂等标记）。
 * 它不是幂等的唯一闸门——最终幂等由 {@link FulfillmentRepository} 的唯一键保证，
 * 本记录用于观测、对账与回溯"这个事件被消费过"。
 */
public interface ProcessedEventRepository {
    void mark(String eventId, Instant consumedAt);
}
