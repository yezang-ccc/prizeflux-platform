package com.prizeflux.campaign.application;

import com.prizeflux.campaign.PrizeFluxCampaignApplication;
import com.prizeflux.campaign.domain.random.RandomSource;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 一次性验证真实 MySQL 中的抽奖、幂等、配额、库存与 Outbox 事务。 */
@Testcontainers
@Import(DrawApplicationIntegrationTest.FixedRuntime.class)
@SpringBootTest(classes = PrizeFluxCampaignApplication.class, properties = {
        "spring.autoconfigure.exclude=",
        "spring.main.web-application-type=none",
        "spring.flyway.enabled=true",
        "prizeflux.persistence.campaign.enabled=true",
        "prizeflux.kafka.draw-queue-enabled=true",
        "PFX_JWT_SECRET=test-only-jwt-secret-0123456789abcdef0123456789"
})
class DrawApplicationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("prizeflux")
            .withUsername("prizeflux")
            .withPassword("integration-only");

    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET time_zone = '+00:00'");
    }

    @Autowired
    private DrawApplicationService service;

    @Autowired
    private DrawQueueApplicationService drawQueue;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private DrawQueueRepository queueRepository;

    @Autowired
    private MutableRandomSource random;

    @Test
    void winningRequestReplaysWithoutConsumingAgain() {
        seed(9201, "1.000000", 1, 1);
        random.value = BigDecimal.ZERO;
        DrawCommand command = new DrawCommand(9201, "request-9201");

        DrawResult first = service.draw(command);
        DrawResult replay = service.draw(command);

        assertThat(first.outcome()).isEqualTo(DrawOutcome.WON);
        assertThat(first.prizeId()).isEqualTo(11L);
        assertThat(first.fulfillmentStatus()).isEqualTo("PENDING");
        assertThat(replay).isEqualTo(first);
        assertThat(count("SELECT remaining_stock FROM prize_inventory WHERE campaign_id = 9201"))
                .isZero();
        assertThat(count("SELECT used_count FROM user_campaign_quota WHERE campaign_id = 9201"))
                .isOne();
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = " + first.id()))
                .isOne();
    }

    @Test
    void successfulDrawExportsTransactionAndDatabaseSegmentTimings() {
        seed(9600, "1.000000", 1, 4);
        random.value = BigDecimal.ZERO;

        double committedBefore = metrics.counter("prizeflux.draw.transactions", "outcome", "committed").count();
        double configLoadsBefore = metrics.counter("prizeflux.draw.database.operations",
                "segment", "config_lock_and_load", "outcome", "success").count();
        double inventoryWritesBefore = metrics.counter("prizeflux.draw.database.operations",
                "segment", "inventory_decrement", "outcome", "success").count();

        assertThat(service.draw(new DrawCommand(9600, "telemetry-draw-9600")).outcome())
                .isEqualTo(DrawOutcome.WON);

        assertThat(metrics.counter("prizeflux.draw.transactions", "outcome", "committed").count())
                .isEqualTo(committedBefore + 1.0);
        assertThat(metrics.timer("prizeflux.draw.transaction", "outcome", "committed").count())
                .isGreaterThan(0L);
        assertThat(metrics.counter("prizeflux.draw.database.operations",
                "segment", "config_lock_and_load", "outcome", "success").count())
                .isEqualTo(configLoadsBefore + 1.0);
        assertThat(metrics.counter("prizeflux.draw.database.operations",
                "segment", "inventory_decrement", "outcome", "success").count())
                .isEqualTo(inventoryWritesBefore + 1.0);
        assertThat(metrics.timer("prizeflux.draw.database.operation",
                "segment", "quota_update", "outcome", "success").count())
                .isGreaterThan(0L);
    }

    @Test
    void failedDrawExportsRollbackAfterSuccessfulEmptyConfigLookup() {
        double rolledBackBefore = metrics.counter("prizeflux.draw.transactions",
                "outcome", "rolled_back").count();
        double successfulConfigLoadsBefore = metrics.counter("prizeflux.draw.database.operations",
                "segment", "config_lock_and_load", "outcome", "success").count();

        assertThatThrownBy(() -> service.draw(new DrawCommand(9699, "missing-campaign-9699")))
                .isInstanceOf(BusinessException.class);

        assertThat(metrics.counter("prizeflux.draw.transactions", "outcome", "rolled_back").count())
                .isEqualTo(rolledBackBefore + 1.0);
        assertThat(metrics.timer("prizeflux.draw.transaction", "outcome", "rolled_back").count())
                .isGreaterThan(0L);
        assertThat(metrics.counter("prizeflux.draw.database.operations",
                "segment", "config_lock_and_load", "outcome", "success").count())
                .isEqualTo(successfulConfigLoadsBefore + 1.0);
    }

    @Test
    void noPrizeAndSoldOutBothPersistWithoutOutbox() {
        seed(9202, "0.200000", 1, 3);
        seed(9203, "1.000000", 0, 3);

        random.value = new BigDecimal("0.5");
        assertThat(service.draw(new DrawCommand(9202, "request-9202")).outcome())
                .isEqualTo(DrawOutcome.NO_PRIZE);
        random.value = BigDecimal.ZERO;
        assertThat(service.draw(new DrawCommand(9203, "request-9203")).outcome())
                .isEqualTo(DrawOutcome.SOLD_OUT);
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id IN "
                + "(SELECT id FROM draw_result WHERE campaign_id IN (9202, 9203))"))
                .isZero();
        assertThat(count("SELECT remaining_stock FROM prize_inventory WHERE campaign_id = 9202"))
                .isOne();
    }

    @Test
    void sameKeyWithAnotherCampaignIsConflict() {
        seed(9204, "0.000000", 1, 3);
        seed(9205, "0.000000", 1, 3);
        random.value = BigDecimal.ZERO;
        service.draw(new DrawCommand(9204, "request-same"));

        assertThatThrownBy(() -> service.draw(new DrawCommand(9205, "request-same")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void quotaFailureRollsBackSecondRequest() {
        seed(9206, "0.000000", 1, 1);
        random.value = BigDecimal.ZERO;
        service.draw(new DrawCommand(9206, "request-9206-a"));
        assertThatThrownBy(() -> service.draw(new DrawCommand(9206, "request-9206-b")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
        assertThat(count("SELECT COUNT(*) FROM draw_request WHERE campaign_id = 9206")).isOne();
        assertThat(count("SELECT used_count FROM user_campaign_quota WHERE campaign_id = 9206"))
                .isOne();
    }

    @Test
    void concurrentRequestsNeverOversellAcrossTenRounds() throws Exception {
        random.value = BigDecimal.ZERO;
        for (int round = 0; round < 10; round++) {
            long campaignId = 9300 + round;
            seed(campaignId, "1.000000", 1, 16);
            List<DrawCommand> commands = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                commands.add(new DrawCommand(campaignId, "parallel-" + round + "-" + index));
            }
            List<DrawResult> results = runTogether(commands);
            assertThat(results).filteredOn(result -> result.outcome() == DrawOutcome.WON).hasSize(1);
            assertThat(results).filteredOn(result -> result.outcome() == DrawOutcome.SOLD_OUT).hasSize(7);
            assertThat(count("SELECT remaining_stock FROM prize_inventory WHERE campaign_id = " + campaignId))
                    .isZero();
            assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id IN "
                    + "(SELECT id FROM draw_result WHERE campaign_id = " + campaignId + ")"))
                    .isOne();
        }
    }

    @Test
    void concurrentSameKeyReturnsOnePersistedResult() throws Exception {
        seed(9400, "1.000000", 1, 16);
        random.value = BigDecimal.ZERO;
        List<DrawCommand> commands = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            commands.add(new DrawCommand(9400, "same-key-9400"));
        }
        List<DrawResult> results = runTogether(commands);
        assertThat(results).hasSize(8).allMatch(result -> result.equals(results.get(0)));
        assertThat(count("SELECT COUNT(*) FROM draw_request WHERE campaign_id = 9400")).isOne();
        assertThat(count("SELECT used_count FROM user_campaign_quota WHERE campaign_id = 9400"))
                .isOne();
        assertThat(count("SELECT remaining_stock FROM prize_inventory WHERE campaign_id = 9400"))
                .isZero();
    }

    @Test
    void queuedDrawsArePersistedThenProcessedIdempotentlyAgainstMysqlStock() {
        seed(9500, "1.000000", 1, 16);
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, role, state)
                VALUES (7, 'tester', ?, 'USER', 'ACTIVE')
                ON DUPLICATE KEY UPDATE role = 'USER', state = 'ACTIVE'
                """, "$2b$12$" + "a".repeat(53));

        DrawQueueView accepted = drawQueue.enqueue(new DrawCommand(9500, "queued-request-9500"));
        assertThat(accepted.status()).isEqualTo("QUEUED");
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'DrawQueued'"))
                .isEqualTo(1);

        drawQueue.process(Long.parseLong(accepted.queueRequestId()));
        drawQueue.process(Long.parseLong(accepted.queueRequestId()));

        DrawQueueView completed = drawQueue.status("queued-request-9500");
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.result().outcome()).isEqualTo(DrawOutcome.WON);
        assertThat(count("SELECT remaining_stock FROM prize_inventory WHERE campaign_id = 9500")).isZero();
        assertThat(count("SELECT used_count FROM user_campaign_quota WHERE campaign_id = 9500")).isOne();
        assertThat(count("SELECT COUNT(*) FROM draw_request WHERE operation = 'QUEUED_DRAW' "
                + "AND campaign_id = 9500")).isOne();
        assertThat(count("SELECT COUNT(*) FROM draw_result WHERE campaign_id = 9500")).isOne();
    }

    @Test
    void queuedAcceptanceRechecksCampaignStateBeforeDrawing() {
        seed(9503, "1.000000", 1, 16);
        ensureQueueUser();
        DrawQueueView accepted = drawQueue.enqueue(new DrawCommand(9503, "closed-after-accept-9503"));
        long id = Long.parseLong(accepted.queueRequestId());
        jdbc.update("UPDATE campaign SET status = 'CLOSED' WHERE id = ?", 9503);

        drawQueue.process(id);

        DrawQueueRepository.Entry rejected = queueRepository.findById(id).orElseThrow();
        assertThat(accepted.status()).isEqualTo("QUEUED");
        assertThat(rejected.status()).isEqualTo(DrawQueueRepository.Status.REJECTED);
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION.code());
        assertThat(count("SELECT COUNT(*) FROM draw_request WHERE campaign_id = 9503")).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_campaign_quota WHERE campaign_id = 9503")).isZero();
        assertThat(count("SELECT remaining_stock FROM prize_inventory WHERE campaign_id = 9503")).isOne();
    }

    @Test
    void expiredLeaseFencesOldWorkerAndAllowsBoundedRetry() {
        seed(9501, "0.000000", 1, 16);
        long userId = ensureQueueUser();
        DrawQueueView accepted = drawQueue.enqueue(new DrawCommand(9501, "lease-recovery-9501"));
        long id = Long.parseLong(accepted.queueRequestId());
        String oldToken = "10000000-0000-0000-0000-000000000001";
        String newToken = "20000000-0000-0000-0000-000000000001";

        assertThat(queueRepository.claim(id, oldToken, Duration.ofSeconds(3))).isTrue();
        assertThat(queueRepository.recordAttempt(id, oldToken, 5)).isTrue();
        jdbc.update("UPDATE draw_queue_request SET lease_expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", id);
        assertThat(queueRepository.claim(id, newToken, Duration.ofSeconds(20))).isTrue();

        assertThat(queueRepository.reject(id, oldToken, ErrorCode.BUSINESS_RULE_VIOLATION.code())).isFalse();
        assertThat(queueRepository.retry(id, newToken, ErrorCode.SERVICE_UNAVAILABLE.code(),
                Duration.ofSeconds(8))).isTrue();
        DrawQueueRepository.Entry recovered = queueRepository.findById(id).orElseThrow();
        assertThat(recovered.userId()).isEqualTo(userId);
        assertThat(recovered.status()).isEqualTo(DrawQueueRepository.Status.RETRY_WAIT);
        assertThat(recovered.attempts()).isOne();
        assertThat(recovered.lastErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.code());
    }

    @Test
    void scannerCompletesCommittedDrawInsteadOfDrawingAgain() {
        seed(9502, "1.000000", 1, 16);
        ensureQueueUser();
        DrawQueueView accepted = drawQueue.enqueue(new DrawCommand(9502, "commit-window-9502"));
        long id = Long.parseLong(accepted.queueRequestId());
        random.value = BigDecimal.ZERO;
        DrawResult committed = service.drawQueued(new DrawCommand(9502, "commit-window-9502"), 7);
        assertThat(queueRepository.findById(id).orElseThrow().status())
                .isEqualTo(DrawQueueRepository.Status.QUEUED);
        assertThat(count("SELECT COUNT(*) FROM draw_result WHERE campaign_id = 9502")).isOne();
        assertThat(count("SELECT remaining_stock FROM prize_inventory WHERE campaign_id = 9502")).isZero();
        jdbc.update("UPDATE draw_queue_request SET next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", id);

        drawQueue.processDue();

        DrawQueueRepository.Entry completed = queueRepository.findById(id).orElseThrow();
        assertThat(completed.status()).isEqualTo(DrawQueueRepository.Status.COMPLETED);
        assertThat(completed.result()).isEqualTo(committed);
        assertThat(count("SELECT COUNT(*) FROM draw_result WHERE campaign_id = 9502")).isOne();
        assertThat(count("SELECT used_count FROM user_campaign_quota WHERE campaign_id = 9502")).isOne();
        assertThat(count("SELECT remaining_stock FROM prize_inventory WHERE campaign_id = 9502")).isZero();
    }

    private long ensureQueueUser() {
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, role, state)
                VALUES (7, 'tester', ?, 'USER', 'ACTIVE')
                ON DUPLICATE KEY UPDATE role = 'USER', state = 'ACTIVE'
                """, "$2b$12$" + "a".repeat(53));
        return 7;
    }

    private List<DrawResult> runTogether(List<DrawCommand> commands) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(commands.size());
        CountDownLatch ready = new CountDownLatch(commands.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<DrawResult>> futures = new ArrayList<>();
            for (DrawCommand command : commands) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent start timed out");
                    }
                    return service.draw(command);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<DrawResult> results = new ArrayList<>();
            for (Future<DrawResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private void seed(long campaignId, String probability, int stock, int dailyLimit) {
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, role, state)
                VALUES (7, 'tester', ?, 'USER', 'ACTIVE')
                ON DUPLICATE KEY UPDATE role = 'USER', state = 'ACTIVE'
                """, "$2b$12$" + "a".repeat(53));
        jdbc.update("""
                INSERT INTO campaign (id, name, starts_at, ends_at, status)
                VALUES (?, '抽奖事务测试', '2026-09-23 00:00:00', '2026-09-24 00:00:00', 'PUBLISHED')
                """, campaignId);
        jdbc.update("""
                INSERT INTO campaign_config_version (campaign_id, config_version, daily_limit, state,
                                                     published_at)
                VALUES (?, 1, ?, 'PUBLISHED', UTC_TIMESTAMP(6))
                """, campaignId, dailyLimit);
        jdbc.update("""
                INSERT INTO prize_config (campaign_id, config_version, prize_id, name, probability, total_stock)
                VALUES (?, 1, 11, '奖品', ?, ?)
                """, campaignId, new BigDecimal(probability), stock);
        jdbc.update("""
                INSERT INTO prize_inventory (campaign_id, config_version, prize_id, remaining_stock)
                VALUES (?, 1, 11, ?)
                """, campaignId, stock);
    }

    static final class MutableRandomSource implements RandomSource {
        volatile BigDecimal value = BigDecimal.ZERO;

        @Override
        public BigDecimal nextUnitInterval() {
            return value;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedRuntime {
        @Bean
        @Primary
        ActorProvider actorProvider() {
            return () -> new AuthenticatedActor(7L, "tester", AppUser.Role.USER);
        }

        @Bean
        @Primary
        MutableRandomSource fixedRandomSource() {
            return new MutableRandomSource();
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}
