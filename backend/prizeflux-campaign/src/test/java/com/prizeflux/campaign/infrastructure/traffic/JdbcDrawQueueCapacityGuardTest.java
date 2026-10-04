package com.prizeflux.campaign.infrastructure.traffic;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.prizeflux.campaign.application.DrawQueueProtectionUnavailableException;
import com.prizeflux.campaign.application.DrawQueueRepository;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcDrawQueueCapacityGuardTest {
    @Test
    void reportsBacklogThresholdAsTheProtectionReason() {
        DrawQueueProtectionProperties properties = properties();
        properties.setMaxBacklog(5);
        JdbcDrawQueueCapacityGuard guard = new JdbcDrawQueueCapacityGuard(mock(DataSource.class), properties);

        Throwable failure = catchThrowable(() -> guard.check(new DrawQueueRepository.PressureSnapshot(5, 0)));

        assertProtectionReason(failure, DrawQueueProtectionUnavailableException.Reason.MYSQL_BACKLOG_LIMIT);
    }

    @Test
    void reportsOldestRequestAgeThresholdSeparately() {
        DrawQueueProtectionProperties properties = properties();
        properties.setMaxOldestAgeSeconds(10);
        JdbcDrawQueueCapacityGuard guard = new JdbcDrawQueueCapacityGuard(mock(DataSource.class), properties);

        Throwable failure = catchThrowable(() -> guard.check(new DrawQueueRepository.PressureSnapshot(1, 10_000)));

        assertProtectionReason(failure,
                DrawQueueProtectionUnavailableException.Reason.MYSQL_OLDEST_REQUEST_AGE_LIMIT);
    }

    @Test
    void reportsConnectionPoolWatermarkSeparately() {
        DrawQueueProtectionProperties properties = properties();
        properties.setDatabasePoolHighWatermark(0.8);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        HikariPoolMXBean pool = mock(HikariPoolMXBean.class);
        when(dataSource.getMaximumPoolSize()).thenReturn(10);
        when(dataSource.getHikariPoolMXBean()).thenReturn(pool);
        when(pool.getActiveConnections()).thenReturn(8);
        JdbcDrawQueueCapacityGuard guard = new JdbcDrawQueueCapacityGuard(dataSource, properties);

        Throwable failure = catchThrowable(() -> guard.check(new DrawQueueRepository.PressureSnapshot(0, 0)));

        assertProtectionReason(failure,
                DrawQueueProtectionUnavailableException.Reason.MYSQL_CONNECTION_POOL_PRESSURE);
    }

    private static DrawQueueProtectionProperties properties() {
        return new DrawQueueProtectionProperties();
    }

    private static void assertProtectionReason(Throwable failure,
            DrawQueueProtectionUnavailableException.Reason expectedReason) {
        assertThat(failure).isInstanceOf(DrawQueueProtectionUnavailableException.class);
        assertThat(((DrawQueueProtectionUnavailableException) failure).reason()).isEqualTo(expectedReason);
    }
}
