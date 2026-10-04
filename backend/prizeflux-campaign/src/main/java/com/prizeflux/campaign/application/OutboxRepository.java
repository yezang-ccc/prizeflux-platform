package com.prizeflux.campaign.application;

import java.time.Instant;
import java.util.List;

/**
 * Outbox 可靠发布的存储端口；调用方（Relay）负责领取与标记之间的节奏。
 * 所有标记操作都以领取标识 {@code leaseToken} 为条件，避免过期 Relay 覆盖新租约。
 */
public interface OutboxRepository {
    /** Cached-sampler input; oldest age covers only non-terminal PENDING/LEASED events. */
    record PressureSnapshot(long pendingCount, long leasedCount, long deadCount, long oldestAgeMillis) {
        public PressureSnapshot {
            if (pendingCount < 0 || leasedCount < 0 || deadCount < 0 || oldestAgeMillis < 0) {
                throw new IllegalArgumentException("Invalid Outbox pressure snapshot");
            }
        }
    }

    /** 原子领取最多 limit 条到期（available_at <= now）的 PENDING 事件，置为 LEASED 并绑定租约。 */
    List<OutboxEvent> claim(int limit, String leaseToken, Instant leaseUntil, Instant now);

    /** Low-frequency operational snapshot; callers must cache it rather than invoke from a scrape. */
    PressureSnapshot pressureSnapshot();

    /** 把已超时（lease_until < now）的 LEASED 事件释放回 PENDING，供下一轮重新领取。 */
    int releaseExpired(Instant now);

    /** 发布成功：领取标识匹配时置为 SENT 并记录 sent_at；返回 false 表示租约已失效。 */
    boolean markSent(String eventId, String leaseToken);

    /** 发布失败但可重试：按给定时间重新变为可领取；领取标识匹配时释放租约。 */
    boolean release(String eventId, String leaseToken, Instant availableAt);

    /** 重试超限：领取标识匹配时置为 DEAD，等待人工/补偿；返回 false 表示租约已失效。 */
    boolean markDead(String eventId, String leaseToken);
}
