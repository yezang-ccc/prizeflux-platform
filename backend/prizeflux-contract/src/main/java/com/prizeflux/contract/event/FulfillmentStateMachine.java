package com.prizeflux.contract.event;

import java.util.Map;
import java.util.Set;

/**
 * 发奖状态机：唯一允许的 {@link FulfillmentStatus} 迁移。规则由 D7-01 落码，Campaign 与 Worker
 * 共用同一判定，非法迁移必须被拒绝而不能静默接受。
 *
 * <pre>
 * PENDING ──► PROCESSING ──► SUCCEEDED（终态）
 *                  │
 *                  ├──► RETRYING ─────► PROCESSING / MANUAL_REVIEW
 *                  └──► MANUAL_REVIEW ► PROCESSING / SUCCEEDED
 * </pre>
 */
public final class FulfillmentStateMachine {
    private static final Map<FulfillmentStatus, Set<FulfillmentStatus>> ALLOWED = Map.of(
            FulfillmentStatus.PENDING, Set.of(FulfillmentStatus.PROCESSING),
            FulfillmentStatus.PROCESSING, Set.of(FulfillmentStatus.SUCCEEDED,
                    FulfillmentStatus.RETRYING, FulfillmentStatus.MANUAL_REVIEW),
            FulfillmentStatus.RETRYING, Set.of(FulfillmentStatus.PROCESSING,
                    FulfillmentStatus.MANUAL_REVIEW),
            FulfillmentStatus.MANUAL_REVIEW, Set.of(FulfillmentStatus.PROCESSING,
                    FulfillmentStatus.SUCCEEDED),
            FulfillmentStatus.SUCCEEDED, Set.of());

    private FulfillmentStateMachine() {
    }

    /** 判断 from → to 是否为合法迁移；任一为 null 一律视为非法。 */
    public static boolean canTransition(FulfillmentStatus from, FulfillmentStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** 校验并返回目标状态；非法迁移抛 {@link IllegalArgumentException}，由调用方映射为业务失败。 */
    public static FulfillmentStatus transition(FulfillmentStatus from, FulfillmentStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalArgumentException("Illegal fulfillment transition: " + from + " -> " + to);
        }
        return to;
    }

    /** 终态：到达后不再有合法出边（当前仅 {@link FulfillmentStatus#SUCCEEDED}）。 */
    public static boolean isTerminal(FulfillmentStatus status) {
        return status == FulfillmentStatus.SUCCEEDED;
    }
}
