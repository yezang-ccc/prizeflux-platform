package com.prizeflux.campaign.infrastructure.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses an isolated MySQL 8.4 instance; never connects to the product database. */
@Testcontainers
class MySqlLockWaitMetricsIntegrationTest {
    private static final String OBSERVER = "pfx_lock_observer_test";
    private static final String OBSERVER_PASSWORD = "test-only-observer-password";
    private static final String BUSINESS_USER = "pfx_lock_probe_test";
    private static final String BUSINESS_PASSWORD = "test-only-business-password";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("prizeflux")
            .withUsername("root")
            .withPassword("test-only-root-password");

    @Test
    void samplesLiveInnoDbWaitsUsingOnlyTheDedicatedPerformanceSchemaTableGrant() throws Exception {
        createProbeAndLeastPrivilegeUsers();
        JdbcTemplate observer = observerJdbcTemplate();

        assertThat(observer.queryForObject(MySqlLockWaitMetrics.SAMPLE_SQL, Long.class)).isZero();
        assertThatThrownBy(() -> observer.queryForObject(
                "SELECT COUNT(*) FROM performance_schema.threads", Long.class))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> observer.queryForObject(
                "SELECT COUNT(*) FROM prizeflux.lock_wait_probe", Long.class))
                .isInstanceOf(DataAccessException.class);

        var registry = new SimpleMeterRegistry();
        var metrics = new MySqlLockWaitMetrics(registry, Clock.systemUTC(),
                observerJdbcUrl(), OBSERVER, OBSERVER_PASSWORD);
        var executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = rootConnection()) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.executeUpdate("UPDATE prizeflux.lock_wait_probe SET counter_value = counter_value + 1 WHERE id = 1");
            }

            var waitingUpdateStarted = new CountDownLatch(1);
            var waitingUpdate = executor.submit(() -> runBlockedUpdate(waitingUpdateStarted));
            assertThat(waitingUpdateStarted.await(5, TimeUnit.SECONDS)).isTrue();

            long observedRelationships = awaitWaitRelationship(observer);
            assertThat(observedRelationships).isPositive();
            metrics.sample();
            assertThat(registry.get("prizeflux.mysql.innodb.lock.wait.relationships").gauge().value())
                    .isEqualTo(observedRelationships);

            blocker.commit();
            assertThat(waitingUpdate.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            metrics.close();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void createProbeAndLeastPrivilegeUsers() throws SQLException {
        try (Connection admin = rootConnection(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE TABLE prizeflux.lock_wait_probe "
                    + "(id INT NOT NULL PRIMARY KEY, counter_value INT NOT NULL)");
            statement.execute("INSERT INTO prizeflux.lock_wait_probe (id, counter_value) VALUES (1, 0)");
            statement.execute("CREATE USER '" + OBSERVER + "'@'%' IDENTIFIED BY '" + OBSERVER_PASSWORD + "'");
            statement.execute("GRANT SELECT ON performance_schema.data_lock_waits TO '" + OBSERVER + "'@'%'");
            statement.execute("CREATE USER '" + BUSINESS_USER + "'@'%' IDENTIFIED BY '" + BUSINESS_PASSWORD + "'");
            statement.execute("GRANT SELECT, UPDATE ON prizeflux.lock_wait_probe TO '" + BUSINESS_USER + "'@'%'");
        }
    }

    private static JdbcTemplate observerJdbcTemplate() {
        var dataSource = new DriverManagerDataSource(observerJdbcUrl(), OBSERVER, OBSERVER_PASSWORD);
        return new JdbcTemplate(dataSource);
    }

    private static String observerJdbcUrl() {
        return MYSQL.getJdbcUrl().replace("/prizeflux", "/");
    }

    private static Connection rootConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static int runBlockedUpdate(CountDownLatch started) throws SQLException {
        try (Connection waiter = DriverManager.getConnection(MYSQL.getJdbcUrl(), BUSINESS_USER, BUSINESS_PASSWORD)) {
            waiter.setAutoCommit(false);
            try (Statement statement = waiter.createStatement()) {
                statement.execute("SET SESSION innodb_lock_wait_timeout = 10");
            }
            started.countDown();
            try (Statement statement = waiter.createStatement()) {
                int affected = statement.executeUpdate(
                        "UPDATE prizeflux.lock_wait_probe SET counter_value = counter_value + 1 WHERE id = 1");
                waiter.commit();
                return affected;
            } catch (SQLException failure) {
                waiter.rollback();
                throw failure;
            }
        }
    }

    private static long awaitWaitRelationship(JdbcTemplate observer) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long observed = 0;
        while (observed == 0 && System.nanoTime() < deadline) {
            observed = observer.queryForObject(MySqlLockWaitMetrics.SAMPLE_SQL, Long.class);
            if (observed == 0) {
                Thread.sleep(25);
            }
        }
        return observed;
    }
}
