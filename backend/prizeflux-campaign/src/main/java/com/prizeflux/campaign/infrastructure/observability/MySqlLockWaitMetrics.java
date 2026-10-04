package com.prizeflux.campaign.infrastructure.observability;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

/** Samples current InnoDB lock-wait relationships through a separate least-privilege connection. */
@Component
@ConditionalOnProperty(name = "prizeflux.observability.mysql-lock-waits.enabled", havingValue = "true")
public final class MySqlLockWaitMetrics {
    static final String SAMPLE_SQL = "SELECT COUNT(*) FROM performance_schema.data_lock_waits";

    private static final Logger log = LoggerFactory.getLogger(MySqlLockWaitMetrics.class);

    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final HikariDataSource diagnosticDataSource;
    private final AtomicLong waitRelationships = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final Counter sampleFailures;

    @Autowired
    public MySqlLockWaitMetrics(MeterRegistry registry,
            Clock clock,
            @Value("${prizeflux.observability.mysql-lock-waits.jdbc-url}") String jdbcUrl,
            @Value("${prizeflux.observability.mysql-lock-waits.username}") String username,
            @Value("${prizeflux.observability.mysql-lock-waits.password:}") String password) {
        this(registry, clock, createDiagnosticDataSource(jdbcUrl, username, password));
    }

    MySqlLockWaitMetrics(MeterRegistry registry, Clock clock, JdbcTemplate jdbc) {
        this(registry, clock, jdbc, null);
    }

    private MySqlLockWaitMetrics(MeterRegistry registry, Clock clock, HikariDataSource dataSource) {
        this(registry, clock, new JdbcTemplate(dataSource), dataSource);
    }

    private MySqlLockWaitMetrics(MeterRegistry registry,
            Clock clock,
            JdbcTemplate jdbc,
            HikariDataSource diagnosticDataSource) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.diagnosticDataSource = diagnosticDataSource;
        Gauge.builder("prizeflux.mysql.innodb.lock.wait.relationships", waitRelationships, AtomicLong::get)
                .description("Sampled current InnoDB data-lock blocking relationships")
                .register(registry);
        Gauge.builder("prizeflux.mysql.lock.wait.sample.last.success.epoch", lastSuccessEpochSeconds,
                        AtomicLong::get)
                .description("Unix timestamp in seconds of the last successful MySQL lock-wait sample")
                .baseUnit("seconds")
                .register(registry);
        this.sampleFailures = registry.counter("prizeflux.mysql.lock.wait.sample.failures");
    }

    @Scheduled(fixedDelayString = "${prizeflux.observability.mysql-lock-waits.sample-delay-ms:10000}")
    public void sample() {
        try {
            Long snapshot = jdbc.queryForObject(SAMPLE_SQL, Long.class);
            if (snapshot == null || snapshot < 0) {
                throw new IllegalStateException("MySQL lock-wait query returned an invalid count");
            }
            waitRelationships.set(snapshot);
            lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
        } catch (RuntimeException failure) {
            sampleFailures.increment();
            log.warn("mysql_lock_wait_sample_failed failureType={}", failure.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void close() {
        if (diagnosticDataSource != null) {
            diagnosticDataSource.close();
        }
    }

    private static HikariDataSource createDiagnosticDataSource(String jdbcUrl, String username, String password) {
        if (jdbcUrl.isBlank() || username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("MySQL lock-wait diagnostics require a URL and dedicated credentials");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("PrizeFluxMySqlLockMetrics");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(2_000);
        config.setValidationTimeout(1_000);
        config.setInitializationFailTimeout(-1);
        config.setReadOnly(true);
        return new HikariDataSource(config);
    }
}
