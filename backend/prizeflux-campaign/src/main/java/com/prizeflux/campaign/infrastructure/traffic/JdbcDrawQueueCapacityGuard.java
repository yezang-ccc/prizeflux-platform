package com.prizeflux.campaign.infrastructure.traffic;

import com.zaxxer.hikari.HikariDataSource;
import com.prizeflux.campaign.application.DrawQueueCapacityGuard;
import com.prizeflux.campaign.application.DrawQueueRepository;
import com.prizeflux.campaign.application.DrawQueueProtectionUnavailableException;
import com.prizeflux.campaign.application.DrawQueueProtectionUnavailableException.Reason;

import javax.sql.DataSource;

/** Sampled backlog and Hikari pool guard. Thresholds shed new work; they are not hard queue limits. */
public final class JdbcDrawQueueCapacityGuard implements DrawQueueCapacityGuard {
    private final DataSource dataSource;
    private final DrawQueueProtectionProperties properties;

    public JdbcDrawQueueCapacityGuard(DataSource dataSource, DrawQueueProtectionProperties properties) {
        if (properties.getMaxBacklog() < 1 || properties.getMaxOldestAgeSeconds() < 1
                || properties.getDatabasePoolHighWatermark() <= 0
                || properties.getDatabasePoolHighWatermark() > 1) {
            throw new IllegalArgumentException("Invalid draw queue capacity configuration");
        }
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @Override
    public void check(DrawQueueRepository.PressureSnapshot pressure) {
        if (pressure.pendingCount() >= properties.getMaxBacklog()) {
            throw new DrawQueueProtectionUnavailableException(Reason.MYSQL_BACKLOG_LIMIT);
        }
        if (pressure.oldestAgeMillis() >= properties.getMaxOldestAgeSeconds() * 1000L) {
            throw new DrawQueueProtectionUnavailableException(Reason.MYSQL_OLDEST_REQUEST_AGE_LIMIT);
        }
        if (dataSource instanceof HikariDataSource hikari) {
            var pool = hikari.getHikariPoolMXBean();
            int threshold = (int) Math.ceil(hikari.getMaximumPoolSize()
                    * properties.getDatabasePoolHighWatermark());
            if (pool != null && pool.getActiveConnections() >= Math.max(1, threshold)) {
                throw new DrawQueueProtectionUnavailableException(Reason.MYSQL_CONNECTION_POOL_PRESSURE);
            }
        }
    }
}
