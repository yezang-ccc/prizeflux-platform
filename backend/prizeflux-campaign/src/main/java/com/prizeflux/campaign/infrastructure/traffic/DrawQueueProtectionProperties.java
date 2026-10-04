package com.prizeflux.campaign.infrastructure.traffic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Local defaults are conservative demonstration thresholds, not production capacity claims. */
@ConfigurationProperties(prefix = "prizeflux.traffic.draw-queue")
public class DrawQueueProtectionProperties {
    private boolean enabled;
    private String keyPrefix = "pfx:draw:queue:admission:v1:";
    private int windowSeconds = 60;
    private int userMaxRequests = 5;
    private int campaignMaxRequests = 300;
    private int globalMaxRequests = 1000;
    private int statusMaxRequests = 60;
    private long maxBacklog = 10000;
    private long maxOldestAgeSeconds = 300;
    private double databasePoolHighWatermark = 0.9;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public int getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    public int getUserMaxRequests() { return userMaxRequests; }
    public void setUserMaxRequests(int userMaxRequests) { this.userMaxRequests = userMaxRequests; }
    public int getCampaignMaxRequests() { return campaignMaxRequests; }
    public void setCampaignMaxRequests(int campaignMaxRequests) { this.campaignMaxRequests = campaignMaxRequests; }
    public int getGlobalMaxRequests() { return globalMaxRequests; }
    public void setGlobalMaxRequests(int globalMaxRequests) { this.globalMaxRequests = globalMaxRequests; }
    public int getStatusMaxRequests() { return statusMaxRequests; }
    public void setStatusMaxRequests(int statusMaxRequests) { this.statusMaxRequests = statusMaxRequests; }
    public long getMaxBacklog() { return maxBacklog; }
    public void setMaxBacklog(long maxBacklog) { this.maxBacklog = maxBacklog; }
    public long getMaxOldestAgeSeconds() { return maxOldestAgeSeconds; }
    public void setMaxOldestAgeSeconds(long maxOldestAgeSeconds) { this.maxOldestAgeSeconds = maxOldestAgeSeconds; }
    public double getDatabasePoolHighWatermark() { return databasePoolHighWatermark; }
    public void setDatabasePoolHighWatermark(double databasePoolHighWatermark) {
        this.databasePoolHighWatermark = databasePoolHighWatermark;
    }
}
