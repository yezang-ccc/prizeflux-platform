package com.prizeflux.campaign.application;

/** 匿名注册入口的流量治理端口；来源地址必须由 Web 容器提供，不接受客户端身份头。 */
public interface RegistrationRateLimiter {
    RegistrationRateLimitDecision consume(String remoteAddress);
}
