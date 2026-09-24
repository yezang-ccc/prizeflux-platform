package com.prizeflux.worker.application;

import com.prizeflux.contract.event.FulfillmentStatus;
import com.prizeflux.contract.event.PrizeFulfillmentEvent;

import java.time.Instant;
import java.util.List;

/**
 * 内部虚拟权益账本端口：为一次中奖写入一笔发奖账（初始状态由调用方给定，通常 PENDING）。
 * 返回 false 表示该事件或同一中奖结果已存在（重复投递/重复发奖），调用方应幂等跳过。
 * 幂等由两处唯一键保证：event_id（同一事件）与 draw_id（同一中奖结果）。
 */
public interface FulfillmentRepository {
    boolean create(PrizeFulfillmentEvent event, FulfillmentStatus status, String payload, Instant now);

    List<FulfillmentClaim> claimDue(Instant now, Instant leaseUntil, String leaseToken, int limit);

    boolean markSucceeded(long id, String leaseToken, Instant now);

    boolean scheduleRetry(long id, String leaseToken, Instant nextAttemptAt, String errorCode, Instant now);

    boolean moveToManualReview(long id, long drawId, String leaseToken, String reasonCode, Instant now);
}
