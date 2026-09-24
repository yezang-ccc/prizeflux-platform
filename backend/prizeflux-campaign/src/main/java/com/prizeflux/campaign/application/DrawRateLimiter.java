package com.prizeflux.campaign.application;

/** 用户抽奖 HTTP 入口的流量治理端口；userId 必须来自服务端认证身份。 */
public interface DrawRateLimiter {
    DrawRateLimitDecision consume(long userId);
}
