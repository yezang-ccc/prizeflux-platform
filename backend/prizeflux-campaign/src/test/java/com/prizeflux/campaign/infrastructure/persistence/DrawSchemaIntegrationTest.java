package com.prizeflux.campaign.infrastructure.persistence;

import com.prizeflux.campaign.PrizeFluxCampaignApplication;
import com.prizeflux.campaign.domain.repository.PrizeInventoryRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 一批验证 V2 迁移、数据库约束与库存条件扣减。 */
@Testcontainers
@SpringBootTest(classes = PrizeFluxCampaignApplication.class, properties = {
        "spring.autoconfigure.exclude=",
        "spring.main.web-application-type=none",
        "spring.flyway.enabled=true",
        "prizeflux.persistence.campaign.enabled=true",
        "PFX_JWT_SECRET=test-only-jwt-secret-0123456789abcdef0123456789"
})
@Transactional
class DrawSchemaIntegrationTest {
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
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PrizeInventoryRepository inventoryRepository;

    @BeforeEach
    void seedFrozenConfiguration() {
        jdbcTemplate.update("""
                INSERT INTO campaign (id, name, starts_at, ends_at, status)
                VALUES (9001, '抽奖迁移测试', UTC_TIMESTAMP(6),
                        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY), 'DRAFT')
                """);
        jdbcTemplate.update("""
                INSERT INTO campaign_config_version (campaign_id, config_version, daily_limit, state)
                VALUES (9001, 1, 3, 'DRAFT')
                """);
        jdbcTemplate.update("""
                INSERT INTO prize_config (campaign_id, config_version, prize_id, name, probability, total_stock)
                VALUES (9001, 1, 11, '奖品', 0.250000, 1)
                """);
        jdbcTemplate.update("""
                INSERT INTO prize_inventory (campaign_id, config_version, prize_id, remaining_stock)
                VALUES (9001, 1, 11, 1)
                """);
    }

    @Test
    void versionTwoMigrationAndInventoryGuardAreActive() {
        assertThat(Arrays.stream(flyway.info().applied())
                .anyMatch(migration -> "2".equals(migration.getVersion().getVersion()))).isTrue();
        assertThat(inventoryRepository.tryDecrement(9001, 1, 11)).isTrue();
        assertThat(inventoryRepository.tryDecrement(9001, 1, 11)).isFalse();
        assertThat(inventoryRepository.remaining(9001, 1, 11)).isZero();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE prize_inventory SET remaining_stock = -1
                WHERE campaign_id = 9001 AND config_version = 1 AND prize_id = 11
                """)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void requestKeyAndResultAreUniquePerBusinessRequest() {
        byte[] fingerprint = new byte[32];
        jdbcTemplate.update("""
                INSERT INTO draw_request (operation, user_id, campaign_id, config_version,
                                          request_key, request_fingerprint, status)
                VALUES ('DRAW', 7, 9001, 1, 'fixed-request-key', ?, 'PENDING')
                """, fingerprint);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO draw_request (operation, user_id, campaign_id, config_version,
                                          request_key, request_fingerprint, status)
                VALUES ('DRAW', 7, 9001, 1, 'fixed-request-key', ?, 'PENDING')
                """, fingerprint)).isInstanceOf(DataAccessException.class);
    }
}
