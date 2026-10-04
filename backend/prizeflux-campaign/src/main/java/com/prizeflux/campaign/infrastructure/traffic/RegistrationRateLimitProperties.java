package com.prizeflux.campaign.infrastructure.traffic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 匿名注册入口的 Redis 固定窗口配置；local-full 显式启用。 */
@ConfigurationProperties(prefix = "prizeflux.traffic.registration-rate-limit")
public class RegistrationRateLimitProperties {
    private boolean enabled;
    private int maxRequests = 5;
    private int windowSeconds = 600;
    private String keyPrefix = "pfx:auth:register:ip:v1:";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxRequests() { return maxRequests; }
    public void setMaxRequests(int maxRequests) { this.maxRequests = maxRequests; }
    public int getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
}
