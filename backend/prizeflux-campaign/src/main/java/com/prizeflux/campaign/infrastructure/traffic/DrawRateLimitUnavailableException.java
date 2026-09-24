package com.prizeflux.campaign.infrastructure.traffic;

/** Redis 限流依赖不可用；不携带底层异常消息给 HTTP 调用者。 */
public final class DrawRateLimitUnavailableException extends RuntimeException {
    public DrawRateLimitUnavailableException() {
        super("Draw rate limiter is unavailable");
    }
}
