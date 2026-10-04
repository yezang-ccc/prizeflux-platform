package com.prizeflux.campaign.infrastructure.persistence;

import com.prizeflux.campaign.PrizeFluxCampaignApplication;
import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.domain.model.CampaignStatus;
import com.prizeflux.campaign.domain.repository.CampaignRepository;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D2-04真实MySQL集成测试：数据库由Testcontainers创建，Schema只允许Flyway建立。
 */
@Testcontainers
@SpringBootTest(
        classes = PrizeFluxCampaignApplication.class,
        properties = {
                "spring.autoconfigure.exclude=",
                "spring.main.web-application-type=none",
                "spring.flyway.enabled=true",
                "prizeflux.persistence.campaign.enabled=true",
                "PFX_JWT_SECRET=test-only-jwt-secret-0123456789abcdef0123456789"
        })
class CampaignRepositoryIntegrationTest {

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
    private ApplicationContext applicationContext;

    @Autowired
    private CampaignMapper campaignMapper;

    @Test
    void flywayAppliesVersionOneMigration() {
        boolean versionOneApplied = Arrays.stream(flyway.info().applied())
                .anyMatch(migration -> "1".equals(migration.getVersion().getVersion()));

        assertThat(versionOneApplied).isTrue();
    }

    @Test
    void campaignTableHasPrimaryKeyAndRequiredChecks() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'campaign'
                """, Integer.class);
        Integer primaryKeyCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'campaign'
                  AND constraint_type = 'PRIMARY KEY'
                """, Integer.class);
        Integer checkCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'campaign'
                  AND constraint_type = 'CHECK'
                """, Integer.class);

        assertThat(tableCount).isOne();
        assertThat(primaryKeyCount).isOne();
        assertThat(checkCount).isGreaterThanOrEqualTo(4);
    }

    @Test
    void databaseRejectsInvalidTimeWindowEvenWhenDomainLayerIsBypassed() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO campaign (id, name, starts_at, ends_at, status)
                VALUES (?, ?, ?, ?, ?)
                """,
                1L,
                "非法时间窗口",
                "2026-09-20 10:00:00.000000",
                "2026-09-20 09:59:59.999999",
                "DRAFT"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_campaign_time_window");
    }

    @Test
    void draftCampaignCanBeSavedAndLoadedThroughDomainPort() {
        CampaignRepository campaignRepository = applicationContext
                .getBeanProvider(CampaignRepository.class)
                .getIfAvailable();
        assertThat(campaignRepository)
                .as("Repository端口应由基础设施适配器实现")
                .isNotNull();

        Campaign draft = new Campaign(
                101L,
                "秋季抽奖",
                Instant.parse("2026-09-20T10:00:00.123456Z"),
                Instant.parse("2026-09-21T10:00:00.654321Z"));

        campaignRepository.save(draft);

        assertThat(campaignRepository.findById(draft.id()))
                .get()
                .usingRecursiveComparison()
                .isEqualTo(draft);
    }

    @Test
    void mapperIsASpringManagedMyBatisProxy() {
        assertThat(Proxy.isProxyClass(campaignMapper.getClass())).isTrue();
    }

    @Test
    void mapperRoundTripsExplicitFieldsIncludingUnicodeName() {
        CampaignPersistenceRecord expected = new CampaignPersistenceRecord(
                102L,
                "夏日🎁抽奖",
                Instant.parse("2026-09-20T10:00:00.123456Z"),
                Instant.parse("2026-09-21T10:00:00.654321Z"),
                "DRAFT");

        assertThat(campaignMapper.insert(expected)).isOne();
        assertThat(campaignMapper.findById(expected.id()))
                .usingRecursiveComparison()
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(CampaignStatus.class)
    void repositoryRoundTripsEveryLegalDomainState(CampaignStatus status) {
        long id = 200L + status.ordinal();
        Campaign draft = new Campaign(
                id,
                "状态往返-" + status.name(),
                Instant.parse("2026-09-22T08:00:00.123456Z"),
                Instant.parse("2026-09-23T08:00:00.654321Z"));
        Campaign expected = switch (status) {
            case DRAFT -> draft;
            case PENDING_REVIEW -> draft.submitForReview();
            case PUBLISHED -> draft.publish();
            case REJECTED -> draft.submitForReview().rejectReview();
            case CLOSED -> draft.close();
        };

        CampaignRepository campaignRepository = applicationContext.getBean(CampaignRepository.class);
        campaignRepository.save(expected);
        Campaign loaded = campaignRepository.findById(id).orElseThrow();

        assertThat(loaded).isNotSameAs(expected);
        assertThat(loaded).usingRecursiveComparison().isEqualTo(expected);
    }

    @Test
    void repositoryValidatesArgumentsAndRepresentsMissingRow() {
        CampaignRepository campaignRepository = applicationContext.getBean(CampaignRepository.class);

        assertThatThrownBy(() -> campaignRepository.save(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
        assertThatThrownBy(() -> campaignRepository.findById(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThat(campaignRepository.findById(999_999L)).isEmpty();
    }

    @Test
    void duplicateIdFailsWithoutOverwritingOriginalRow() {
        CampaignRepository campaignRepository = applicationContext.getBean(CampaignRepository.class);
        Campaign original = new Campaign(
                301L,
                "原活动",
                Instant.parse("2026-09-24T08:00:00.000001Z"),
                Instant.parse("2026-09-25T08:00:00.000002Z"));
        Campaign duplicate = new Campaign(
                301L,
                "不应覆盖",
                Instant.parse("2026-09-26T08:00:00.000003Z"),
                Instant.parse("2026-09-27T08:00:00.000004Z"));

        campaignRepository.save(original);
        assertThatThrownBy(() -> campaignRepository.save(duplicate))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(campaignRepository.findById(301L))
                .get()
                .usingRecursiveComparison()
                .isEqualTo(original);
    }

    @Test
    void conditionalStatusUpdateSucceedsWhenExpectedStateMatches() {
        CampaignRepository campaignRepository = applicationContext.getBean(CampaignRepository.class);
        Campaign draft = new Campaign(
                302L,
                "条件更新成功",
                Instant.parse("2026-10-02T08:00:00Z"),
                Instant.parse("2026-10-03T08:00:00Z"));
        campaignRepository.save(draft);

        boolean updated = campaignRepository.updateStatus(
                draft.id(), CampaignStatus.DRAFT, CampaignStatus.PUBLISHED);

        assertThat(updated).isTrue();
        assertThat(campaignRepository.findById(draft.id()))
                .get()
                .extracting(Campaign::status)
                .isEqualTo(CampaignStatus.PUBLISHED);
    }

    @Test
    void conditionalStatusUpdateDoesNotOverwriteChangedState() {
        CampaignRepository campaignRepository = applicationContext.getBean(CampaignRepository.class);
        Campaign draft = new Campaign(
                303L,
                "条件更新冲突",
                Instant.parse("2026-10-04T08:00:00Z"),
                Instant.parse("2026-10-05T08:00:00Z"));
        campaignRepository.save(draft);
        assertThat(campaignRepository.updateStatus(
                draft.id(), CampaignStatus.DRAFT, CampaignStatus.PUBLISHED)).isTrue();

        boolean updated = campaignRepository.updateStatus(
                draft.id(), CampaignStatus.DRAFT, CampaignStatus.CLOSED);

        assertThat(updated).isFalse();
        assertThat(campaignRepository.findById(draft.id()))
                .get()
                .extracting(Campaign::status)
                .isEqualTo(CampaignStatus.PUBLISHED);
    }

    @Test
    void adapterRejectsUnknownPersistedStatus() {
        CampaignMapper mapper = mock(CampaignMapper.class);
        when(mapper.findById(401L)).thenReturn(new CampaignPersistenceRecord(
                401L,
                "损坏状态",
                Instant.parse("2026-09-28T08:00:00Z"),
                Instant.parse("2026-09-29T08:00:00Z"),
                "BROKEN"));
        CampaignRepository repository = new MyBatisCampaignRepository(mapper);

        assertThatThrownBy(() -> repository.findById(401L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown persisted campaign status: BROKEN");
    }

    @Test
    void adapterRejectsUnexpectedAffectedRowCount() {
        CampaignMapper mapper = mock(CampaignMapper.class);
        when(mapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(0);
        CampaignRepository repository = new MyBatisCampaignRepository(mapper);
        Campaign campaign = new Campaign(
                501L,
                "受影响行数",
                Instant.parse("2026-09-30T08:00:00Z"),
                Instant.parse("2026-10-01T08:00:00Z"));

        assertThatThrownBy(() -> repository.save(campaign))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected one inserted campaign row, but got 0");
    }

    @Test
    void adapterRejectsUnexpectedUpdatedRowCount() {
        CampaignMapper mapper = mock(CampaignMapper.class);
        when(mapper.updateStatus(502L, "DRAFT", "PUBLISHED")).thenReturn(2);
        CampaignRepository repository = new MyBatisCampaignRepository(mapper);

        assertThatThrownBy(() -> repository.updateStatus(
                502L, CampaignStatus.DRAFT, CampaignStatus.PUBLISHED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected zero or one updated campaign row, but got 2");
    }
}
