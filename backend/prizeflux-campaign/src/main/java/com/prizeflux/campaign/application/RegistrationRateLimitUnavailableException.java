package com.prizeflux.campaign.application;

/** 注册流量治理存储不可用；调用方必须失败关闭。 */
public final class RegistrationRateLimitUnavailableException extends RuntimeException {
    public RegistrationRateLimitUnavailableException() {
        super("Registration rate limiter is unavailable");
    }
}
