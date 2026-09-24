package com.prizeflux.campaign.infrastructure.traffic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 仅应用于受认证保护的用户抽奖入口。 */
@ConfigurationProperties(prefix = "prizeflux.traffic.draw-rate-limit")
public class DrawRateLimitProperties {
    private boolean enabled;
    private int maxRequests = 30;
    private int windowSeconds = 60;
    private String keyPrefix = "pfx:draw:rate:v1:";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    public void setMaxRequests(int maxRequests) {
        this.maxRequests = maxRequests;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
