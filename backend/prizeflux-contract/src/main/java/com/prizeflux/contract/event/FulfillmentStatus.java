package com.prizeflux.contract.event;

/**
 * 发奖状态的稳定契约，跨 Campaign 与 Worker 共享。
 * 值与 {@code draw_result.fulfillment_status} 的 CHECK 约束同名，可用 {@link #name()} 直接持久化。
 */
public enum FulfillmentStatus {
    /** 中奖已记录、发奖事件已入 Outbox，Worker 尚未开始处理。 */
    PENDING,
    /** Worker 已领取并正在执行发奖。 */
    PROCESSING,
    /** 发奖成功（终态）。 */
    SUCCEEDED,
    /** 短暂失败，等待指数退避重试。 */
    RETRYING,
    /** 无法自动判定或需要人工介入，等待人工处理。 */
    MANUAL_REVIEW
}
