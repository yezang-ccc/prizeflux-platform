package com.prizeflux.campaign.application;

/** 安全的限流决策；Redis 实现细节不越过基础设施边界。 */
public record DrawRateLimitDecision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
    public DrawRateLimitDecision {
        if (limit < 0 || remaining < 0 || remaining > limit || retryAfterSeconds < 0) {
            throw new IllegalArgumentException("Invalid draw rate-limit decision");
        }
        if (!allowed && limit == 0) {
            throw new IllegalArgumentException("A denied decision must have an active limit");
        }
    }

    /** 未启用流量治理时仅用于轻量开发与单测的决策。 */
    public static DrawRateLimitDecision unlimited() {
        return new DrawRateLimitDecision(true, 0, 0, 0);
    }
}
