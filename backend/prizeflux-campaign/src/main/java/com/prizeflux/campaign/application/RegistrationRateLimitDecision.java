package com.prizeflux.campaign.application;

/** 匿名注册 IP 限流的一次判断结果。 */
public record RegistrationRateLimitDecision(boolean allowed, int limit, int remaining,
        long retryAfterSeconds) {
    public RegistrationRateLimitDecision {
        if (limit < 1 || remaining < 0 || remaining > limit || retryAfterSeconds < 0
                || (allowed && (remaining < 0 || retryAfterSeconds != 0))
                || (!allowed && (remaining != 0 || retryAfterSeconds < 1))) {
            throw new IllegalArgumentException("Invalid registration rate-limit decision");
        }
    }

    public static RegistrationRateLimitDecision unlimited() {
        return new RegistrationRateLimitDecision(true, Integer.MAX_VALUE,
                Integer.MAX_VALUE, 0);
    }
}
