package com.prizeflux.campaign.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.PrizeFluxCampaignApplication;
import com.prizeflux.campaign.application.AppUser;
import com.prizeflux.campaign.application.CampaignConfigurationService;
import com.prizeflux.campaign.application.PasswordHasher;
import com.prizeflux.campaign.application.TokenService;
import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.domain.model.CampaignStatus;
import com.prizeflux.campaign.domain.model.Prize;
import com.prizeflux.campaign.domain.repository.CampaignRepository;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 生产MVC→应用→MyBatis→真实MySQL 8.4的活动API集成测试。 */
@Testcontainers
@SpringBootTest(
        classes = PrizeFluxCampaignApplication.class,
        properties = {
                "spring.autoconfigure.exclude=",
                "spring.flyway.enabled=true",
                "prizeflux.persistence.campaign.enabled=true",
                "prizeflux.kafka.draw-queue-enabled=true",
                "management.prometheus.metrics.export.enabled=true",
                "management.endpoints.web.exposure.include=prometheus",
                "management.endpoint.prometheus.access=read-only",
                "pfx.observability.metrics.scrape-auth-enabled=true",
                "pfx.observability.metrics.scrape-username=test-scraper",
                "pfx.observability.metrics.scrape-password=test-only-scrape-secret",
                "PFX_JWT_SECRET=test-only-jwt-secret-0123456789abcdef0123456789"
        })
@AutoConfigureMockMvc
@Import(CampaignApiIntegrationTest.RepositoryControlConfiguration.class)
class CampaignApiIntegrationTest {

    private static final String FAKE_SECRET = "FAKE_SECRET_D2_05";

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
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private PasswordHasher passwordHasher;

    private String operatorToken;
    private String userToken;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ControllableCampaignRepository campaignRepository;

    @Autowired
    private CampaignConfigurationService configurationService;

    @BeforeEach
    void clearCampaigns() {
        campaignRepository.reset();
        operatorToken = tokenService.issue(new AppUser(1L, "test.operator",
                passwordHasher.hash("test-only-password"), AppUser.Role.OPERATOR,
                AppUser.State.ACTIVE)).value();
        userToken = tokenService.issue(new AppUser(2L, "test.user",
                passwordHasher.hash("test-only-password"), AppUser.Role.USER,
                AppUser.State.ACTIVE)).value();
        jdbcTemplate.update("""
                INSERT INTO app_user (id, username, password_hash, role, state)
                VALUES (1, 'test.operator', ?, 'OPERATOR', 'ACTIVE')
                ON DUPLICATE KEY UPDATE username = VALUES(username), role = VALUES(role), state = VALUES(state)
                """, passwordHasher.hash("test-only-password"));
        jdbcTemplate.update("""
                INSERT INTO app_user (id, username, password_hash, role, state)
                VALUES (2, 'test.user', ?, 'USER', 'ACTIVE')
                ON DUPLICATE KEY UPDATE username = VALUES(username), role = VALUES(role), state = VALUES(state)
                """, passwordHasher.hash("test-only-password"));
        jdbcTemplate.update("DELETE FROM draw_queue_request");
        jdbcTemplate.update("DELETE FROM campaign_review_decision");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM draw_result");
        jdbcTemplate.update("DELETE FROM draw_request");
        jdbcTemplate.update("DELETE FROM user_campaign_quota");
        jdbcTemplate.update("DELETE FROM prize_inventory");
        jdbcTemplate.update("DELETE FROM prize_config");
        jdbcTemplate.update("DELETE FROM campaign_config_version");
        jdbcTemplate.update("DELETE FROM campaign");
    }

    @Test
    void productionCampaignControllerIsScanned() {
        assertThat(applicationContext.containsBean("campaignController")).isTrue();
    }

    @Test
    void prometheusEndpointRequiresOperatorAuthenticationAndExportsRuntimeMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());

        performAs(userToken, get("/actuator/prometheus"))
                .andExpect(status().isForbidden());

        performAs(operatorToken, get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("# HELP jvm_memory_used_bytes")))
                .andExpect(content().string(containsString("hikaricp_connections_active")));

        mockMvc.perform(get("/actuator/prometheus").header("Authorization", scraperAuthorization()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/campaigns/2001")
                        .header("Authorization", scraperAuthorization()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createThenGetReturnsCompleteDraftAndPersistsIt() throws Exception {
        perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(2001L, "秋季抽奖")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("PFX-0000"))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data.id").value(2001L))
                .andExpect(jsonPath("$.data.name").value("秋季抽奖"))
                .andExpect(jsonPath("$.data.startsAt").value("2026-10-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.endsAt").value("2026-10-08T00:00:00Z"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        perform(get("/api/campaigns/{id}", 2001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(2001L))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        assertThat(databaseStatus(2001L)).isEqualTo("DRAFT");
    }

    @Test
    void publishThenCloseReturnsAndPersistsEachNewState() throws Exception {
        createCampaign(2002L, "状态活动");

        perform(post("/api/campaigns/{id}/publish", 2002L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(2002L))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        assertThat(databaseStatus(2002L)).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT remaining_stock FROM prize_inventory WHERE campaign_id = 2002",
                Integer.class)).isEqualTo(2);

        perform(post("/api/campaigns/{id}/close", 2002L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(2002L))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
        assertThat(databaseStatus(2002L)).isEqualTo("CLOSED");
    }

    @Test
    void draftWithoutPrizeConfigurationCannotBePublished() throws Exception {
        perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(2010L, "待配置活动")))
                .andExpect(status().isCreated());

        assertFailure(perform(post("/api/campaigns/{id}/publish", 2010L)),
                422, "PFX-2001", "业务规则不满足");
        assertThat(databaseStatus(2010L)).isEqualTo("DRAFT");
    }

    @Test
    void invalidReplacementAndPublishedModificationAreRejected() throws Exception {
        createCampaign(2011L, "配置校验活动");
        assertThatThrownBy(() -> configurationService.configureDraft(2011L, 3, List.of(
                new Prize(11L, 2011L, "一", 1, new BigDecimal("0.8")),
                new Prize(12L, 2011L, "二", 1, new BigDecimal("0.3")))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prize_config WHERE campaign_id = 2011", Integer.class))
                .isOne();

        perform(post("/api/campaigns/{id}/publish", 2011L))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> configurationService.configureDraft(2011L, 3,
                List.of(new Prize(11L, 2011L, "一", 1, new BigDecimal("0.2")))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
    }

    @Test
    void invalidInputsReturnSafe400Responses() throws Exception {
        assertFailure(perform(get("/api/campaigns/{id}", 0)),
                400, "PFX-1001", "参数无效");
        assertFailure(perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json")),
                400, "PFX-1001", "参数无效");
        assertFailure(perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(0, " "))),
                400, "PFX-1001", "参数无效");
    }

    @Test
    void invalidDomainTimeWindowReturns400() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", 2003L);
        body.put("name", "非法时间");
        body.put("startsAt", "2026-10-08T00:00:00Z");
        body.put("endsAt", "2026-10-01T00:00:00Z");

        assertFailure(perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))),
                400, "PFX-1001", "参数无效");
    }

    @Test
    void missingCampaignReturns404() throws Exception {
        assertFailure(perform(get("/api/campaigns/{id}", 2999L)),
                404, "PFX-2002", "资源不存在");
    }

    @Test
    void userAndOperatorCampaignReadsAreRoleAndStateFiltered() throws Exception {
        createCampaign(2201L, "用户不可见草稿");
        createCampaign(2202L, "用户可见已发布");
        perform(post("/api/campaigns/{id}/publish", 2202L)).andExpect(status().isOk());
        createCampaign(2203L, "用户不可见关闭");
        perform(post("/api/campaigns/{id}/publish", 2203L)).andExpect(status().isOk());
        perform(post("/api/campaigns/{id}/close", 2203L)).andExpect(status().isOk());

        performAs(userToken, get("/api/available-campaigns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(2202)))
                .andExpect(jsonPath("$.data.items[0].status").value("PUBLISHED"));
        perform(get("/api/ops/campaigns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(2201, 2202, 2203)));
        assertFailure(performAs(userToken, get("/api/campaigns/{id}", 2201L)),
                404, "PFX-2002", "资源不存在");
        performAs(userToken, get("/api/campaigns/{id}", 2202L))
                .andExpect(status().isOk());
        assertFailure(performAs(userToken, get("/api/ops/campaigns")),
                403, "PFX-1003", "无权限");
        assertFailure(performAs(operatorToken, get("/api/available-campaigns")),
                403, "PFX-1003", "无权限");
        mockMvc.perform(get("/api/available-campaigns"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userOwnedCampaignCanBeConfiguredSubmittedAndApproved() throws Exception {
        String request = """
                {"id":999999,"ownerUserId":1,"status":"PUBLISHED","role":"OPERATOR",
                 "name":"用户活动","startsAt":"2026-10-01T00:00:00Z","endsAt":"2026-10-08T00:00:00Z"}
                """;
        var created = performAs(userToken, post("/api/me/campaigns")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        assertThat(id).isPositive().isNotEqualTo(999999L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_user_id FROM campaign WHERE id = ?", Long.class, id)).isEqualTo(2L);

        performAs(userToken, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/me/campaigns/{id}/configuration", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(3, 11, "一等奖", 2, "0.25")))
                .andExpect(status().isOk());
        String otherToken = tokenService.issue(new AppUser(3L, "other.user",
                        passwordHasher.hash("test-only-password"), AppUser.Role.USER,
                        AppUser.State.ACTIVE)).value();
        performAs(otherToken, get("/api/me/campaigns/{id}", id))
                .andExpect(status().isNotFound());
        performAs(otherToken, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/me/campaigns/{id}/configuration", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(1, 11, "越权奖品", 99, "1")))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prize_config WHERE campaign_id = ?", Integer.class, id)).isOne();

        performAs(userToken, post("/api/me/campaigns/{id}/submit", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        assertThat(databaseStatus(id)).isEqualTo("PENDING_REVIEW");

        perform(get("/api/ops/reviews/campaigns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(id))
                .andExpect(jsonPath("$.data.items[0].ownerUsername").value("test.user"));
        perform(post("/api/ops/reviews/campaigns/{id}/approve", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"配置完整\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        assertThat(databaseStatus(id)).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM campaign_config_version WHERE campaign_id = ? AND config_version = 1
                """, String.class, id)).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT remaining_stock FROM prize_inventory WHERE campaign_id = ?", Integer.class, id))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM campaign_review_decision WHERE campaign_id = ?", Integer.class, id))
                .isOne();
        assertFailure(perform(post("/api/ops/reviews/campaigns/{id}/approve", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")),
                409, "PFX-2003", "资源冲突");
        performAs(userToken, get("/api/me/campaigns/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewComment").value("配置完整"));
        performAs(userToken, get("/api/available-campaigns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(id));
    }

    @Test
    void rejectedCampaignCanBeEditedAndResubmittedButPendingCampaignIsLocked() throws Exception {
        var created = performAs(userToken, post("/api/me/campaigns")
                        .contentType(MediaType.APPLICATION_JSON).content(ownedBody("待驳回活动")))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        performAs(userToken, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/me/campaigns/{id}/configuration", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(2, 31, "奖品", 3, "0.5")))
                .andExpect(status().isOk());
        performAs(userToken, post("/api/me/campaigns/{id}/submit", id)).andExpect(status().isOk());
        performAs(userToken, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/me/campaigns/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content(ownedBody("不允许在审核中编辑")))
                .andExpect(status().isUnprocessableEntity());
        perform(post("/api/ops/reviews/campaigns/{id}/reject", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"改\"}"))
                .andExpect(status().isBadRequest());
        perform(post("/api/ops/reviews/campaigns/{id}/reject", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"请补充活动规则\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        performAs(userToken, get("/api/me/campaigns/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewComment").value("请补充活动规则"));
        performAs(userToken, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/me/campaigns/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content(ownedBody("已按要求修改")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        performAs(userToken, post("/api/me/campaigns/{id}/submit", id)).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM campaign_review_decision WHERE campaign_id = ?", Integer.class, id))
                .isOne();
    }

    @Test
    void userDraftValidationReusesCampaignRulesWithoutWritingCampaigns() throws Exception {
        performAs(userToken, post("/api/me/campaigns/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"合法草案","startsAt":"2026-10-01T00:00:00Z","endsAt":"2026-10-08T00:00:00Z",
                                 "dailyLimit":3,"prizes":[{"id":1,"name":"礼盒","totalStock":2,"probability":0.25}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.issues").isEmpty())
                .andExpect(jsonPath("$.data.findings").isEmpty());
        performAs(userToken, post("/api/me/campaigns/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"概率错误","startsAt":"2026-10-01T00:00:00Z","endsAt":"2026-10-08T00:00:00Z",
                                 "dailyLimit":3,"prizes":[
                                  {"id":1,"name":"甲","totalStock":2,"probability":0.7},
                                  {"id":2,"name":"乙","totalStock":2,"probability":0.5}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.issues[0]").value(org.hamcrest.Matchers.containsString("概率")))
                .andExpect(jsonPath("$.data.findings[0].code")
                        .value("CAMPAIGN_PRIZE_PROBABILITY_TOTAL_EXCEEDED"))
                .andExpect(jsonPath("$.data.findings[0].field").value("prizes"));
        performAs(userToken, post("/api/me/campaigns/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"精度错误","startsAt":"2026-10-01T00:00:00Z","endsAt":"2026-10-08T00:00:00Z",
                                 "dailyLimit":3,"prizes":[{"id":1,"name":"奖品","totalStock":2,"probability":0.0000001}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.findings[0].code")
                        .value("CAMPAIGN_PRIZE_PROBABILITY_PRECISION_UNSUPPORTED"))
                .andExpect(jsonPath("$.data.findings[0].field").value("prizes[0].probability"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM campaign", Integer.class)).isZero();
        assertFailure(perform(post("/api/me/campaigns/validate")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")),
                403, "PFX-1003", "无权限");
    }

    @Test
    void concurrentReviewDecisionsCommitOnlyOneStatusAndAuditRow() throws Exception {
        var created = performAs(userToken, post("/api/me/campaigns")
                        .contentType(MediaType.APPLICATION_JSON).content(ownedBody("并发审核活动")))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        performAs(userToken, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/me/campaigns/{id}/configuration", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configureBody(2, 41, "奖品", 1, "0.5")))
                .andExpect(status().isOk());
        performAs(userToken, post("/api/me/campaigns/{id}/submit", id)).andExpect(status().isOk());

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var approve = executor.submit(() -> concurrentDecision(start, id, true));
            var reject = executor.submit(() -> concurrentDecision(start, id, false));
            List<Integer> statuses = List.of(approve.get(15, TimeUnit.SECONDS), reject.get(15, TimeUnit.SECONDS))
                    .stream().sorted().toList();
            assertThat(statuses).containsExactly(200, 409);
        } finally {
            executor.shutdownNow();
        }
        String finalStatus = databaseStatus(id);
        assertThat(finalStatus).isIn("PUBLISHED", "REJECTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM campaign_review_decision WHERE campaign_id = ?", Integer.class, id)).isOne();
        int inventoryRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prize_inventory WHERE campaign_id = ?", Integer.class, id);
        assertThat(inventoryRows).isEqualTo(finalStatus.equals("PUBLISHED") ? 1 : 0);
    }

    @Test
    void queuedDrawReturnsAcceptedAndOnlyOwnerCanReadItsStatus() throws Exception {
        createCampaign(2301L, "排队开奖活动");
        perform(post("/api/campaigns/{id}/publish", 2301L)).andExpect(status().isOk());

        String request = """
                {"campaignId":2301,"requestKey":"queue-api-2301"}
                """;
        var first = performAs(userToken, post("/api/draw-queue")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.result").value(nullValue()))
                .andReturn();
        String queueId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("queueRequestId").asText();

        performAs(userToken, post("/api/draw-queue")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.queueRequestId").value(queueId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draw_queue_request WHERE user_id = 2 AND request_key = ?",
                Integer.class, "queue-api-2301")).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'DrawQueued'", Integer.class)).isOne();

        String otherToken = tokenService.issue(new AppUser(3L, "other.user",
                passwordHasher.hash("test-only-password"), AppUser.Role.USER,
                AppUser.State.ACTIVE)).value();
        assertFailure(performAs(otherToken, get("/api/me/draw-queue/{key}", "queue-api-2301")),
                404, "PFX-2002", "资源不存在");
        assertFailure(perform(get("/api/me/draw-queue/{key}", "queue-api-2301")),
                403, "PFX-1003", "无权限");
        mockMvc.perform(get("/api/me/draw-queue/{key}", "queue-api-2301"))
                .andExpect(status().isUnauthorized());
    }

    private int concurrentDecision(CyclicBarrier start, long id, boolean approve) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        String path = "/api/ops/reviews/campaigns/{id}/" + (approve ? "approve" : "reject");
        String body = approve ? "{\"comment\":\"\"}" : "{\"comment\":\"需要修改\"}";
        return perform(post(path, id).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void campaignReadPaginationIsStableAndPrizeViewsDoNotExposeStock() throws Exception {
        createCampaign(2210L, "分页一");
        createCampaign(2211L, "分页二");
        createCampaign(2212L, "分页三");
        for (long id : List.of(2210L, 2211L, 2212L)) {
            perform(post("/api/campaigns/{id}/publish", id)).andExpect(status().isOk());
        }

        var first = perform(get("/api/ops/campaigns").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(2212))
                .andExpect(jsonPath("$.data.items[1].id").value(2211))
                .andExpect(jsonPath("$.data.nextCursor").value(2211))
                .andReturn();
        String cursor = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("nextCursor").asText();
        perform(get("/api/ops/campaigns").param("limit", "2").param("before", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(2210)));

        int remainingBefore = jdbcTemplate.queryForObject(
                "SELECT remaining_stock FROM prize_inventory WHERE campaign_id = 2212", Integer.class);
        performAs(userToken, get("/api/campaigns/{id}/prizes", 2212L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(11))
                .andExpect(jsonPath("$.data[0].probability").value(0.25))
                .andExpect(content().string(not(containsString("totalStock"))))
                .andExpect(content().string(not(containsString("remainingStock"))));
        perform(get("/api/ops/campaigns/{id}/configuration", 2212L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyLimit").value(3))
                .andExpect(jsonPath("$.data.prizes[0].totalStock").value(2));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT remaining_stock FROM prize_inventory WHERE campaign_id = 2212", Integer.class))
                .isEqualTo(remainingBefore);
    }

    @Test
    void campaignReadRejectsInvalidPaginationAndKeepsUserAwayFromDraftPrizes() throws Exception {
        performAs(userToken, get("/api/available-campaigns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
        createCampaign(2220L, "分页参数与草稿奖品");
        assertFailure(perform(get("/api/ops/campaigns").param("limit", "101")),
                400, "PFX-1001", "参数无效");
        assertFailure(perform(get("/api/ops/campaigns").param("limit", "0")),
                400, "PFX-1001", "参数无效");
        assertFailure(perform(get("/api/ops/campaigns").param("before", "0")),
                400, "PFX-1001", "参数无效");
        assertFailure(performAs(userToken, get("/api/campaigns/{id}/prizes", 2220L)),
                404, "PFX-2002", "资源不存在");
    }

    @Test
    void duplicateIdReturns409WithoutOverwritingOriginal() throws Exception {
        createCampaign(2004L, "原活动");

        assertFailure(perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(2004L, "不应覆盖"))),
                409, "PFX-2003", "资源冲突");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM campaign WHERE id = ?", String.class, 2004L))
                .isEqualTo("原活动");
    }

    @Test
    void repeatedStateTransitionsReturn422() throws Exception {
        createCampaign(2005L, "重复迁移");
        perform(post("/api/campaigns/{id}/publish", 2005L))
                .andExpect(status().isOk());

        assertFailure(perform(post("/api/campaigns/{id}/publish", 2005L)),
                422, "PFX-2001", "业务规则不满足");
        perform(post("/api/campaigns/{id}/close", 2005L))
                .andExpect(status().isOk());
        assertFailure(perform(post("/api/campaigns/{id}/close", 2005L)),
                422, "PFX-2001", "业务规则不满足");
    }

    @Test
    void conditionalPublicationFailureRollsBackConfigurationAndStatus() throws Exception {
        createCampaign(2006L, "条件冲突");
        campaignRepository.beforeNextUpdate(() -> jdbcTemplate.update(
                "UPDATE campaign SET status = 'CLOSED' WHERE id = ?", 2006L));

        assertFailure(perform(post("/api/campaigns/{id}/publish", 2006L)),
                409, "PFX-2003", "资源冲突");
        assertThat(databaseStatus(2006L)).isEqualTo("DRAFT");
    }

    @Test
    void unexpectedFailureReturnsSafe500() throws Exception {
        createCampaign(2007L, "安全500");
        campaignRepository.failNextFind(new IllegalStateException(FAKE_SECRET));

        perform(get("/api/campaigns/{id}", 2007L))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PFX-9999"))
                .andExpect(jsonPath("$.message").value("服务内部错误"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().string(not(containsString(FAKE_SECRET))));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return performAs(operatorToken, request);
    }

    private ResultActions performAs(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private static String scraperAuthorization() {
        String credentials = Base64.getEncoder().encodeToString(
                "test-scraper:test-only-scrape-secret".getBytes(StandardCharsets.UTF_8));
        return "Basic " + credentials;
    }

    private void createCampaign(long id, String name) throws Exception {
        perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(id, name)))
                .andExpect(status().isCreated());
        configurationService.configureDraft(id, 3,
                List.of(new Prize(11L, id, "奖品", 2, new BigDecimal("0.25"))));
    }

    private String createBody(long id, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("name", name);
        body.put("startsAt", "2026-10-01T00:00:00Z");
        body.put("endsAt", "2026-10-08T00:00:00Z");
        return objectMapper.writeValueAsString(body);
    }

    private String ownedBody(String name) {
        return """
                {"name":"%s","startsAt":"2026-10-01T00:00:00Z","endsAt":"2026-10-08T00:00:00Z"}
                """.formatted(name);
    }

    private String configureBody(int dailyLimit, long prizeId, String prizeName,
            int totalStock, String probability) {
        return """
                {"dailyLimit":%d,"prizes":[{"id":%d,"name":"%s","totalStock":%d,"probability":%s}]}
                """.formatted(dailyLimit, prizeId, prizeName, totalStock, probability);
    }

    private String databaseStatus(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM campaign WHERE id = ?", String.class, id);
    }

    private void assertFailure(
            ResultActions result, int httpStatus, String code, String message) throws Exception {
        result.andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    /** 只控制故障时点；所有正常读写仍委托生产MyBatis适配器。 */
    static final class ControllableCampaignRepository implements CampaignRepository {
        private final CampaignRepository delegate;
        private Runnable beforeNextUpdate;
        private RuntimeException nextFindFailure;

        ControllableCampaignRepository(CampaignRepository delegate) {
            this.delegate = delegate;
        }

        void beforeNextUpdate(Runnable action) {
            beforeNextUpdate = action;
        }

        void failNextFind(RuntimeException failure) {
            nextFindFailure = failure;
        }

        void reset() {
            beforeNextUpdate = null;
            nextFindFailure = null;
        }

        @Override
        public void save(Campaign campaign) {
            delegate.save(campaign);
        }

        @Override
        public Campaign createOwned(String name, java.time.Instant startsAt,
                java.time.Instant endsAt, long ownerUserId) {
            return delegate.createOwned(name, startsAt, endsAt, ownerUserId);
        }

        @Override
        public Optional<Campaign> findOwnedById(long id, long ownerUserId) {
            return delegate.findOwnedById(id, ownerUserId);
        }

        @Override
        public boolean updateOwnedDetails(long id, long ownerUserId, String name,
                java.time.Instant startsAt, java.time.Instant endsAt) {
            return delegate.updateOwnedDetails(id, ownerUserId, name, startsAt, endsAt);
        }

        @Override
        public boolean submitOwnedForReview(long id, long ownerUserId) {
            return delegate.submitOwnedForReview(id, ownerUserId);
        }

        @Override
        public Optional<Campaign> findById(long id) {
            if (nextFindFailure != null) {
                RuntimeException failure = nextFindFailure;
                nextFindFailure = null;
                throw failure;
            }
            return delegate.findById(id);
        }

        @Override
        public boolean updateStatus(
                long id, CampaignStatus expectedStatus, CampaignStatus targetStatus) {
            if (beforeNextUpdate != null) {
                Runnable action = beforeNextUpdate;
                beforeNextUpdate = null;
                action.run();
            }
            return delegate.updateStatus(id, expectedStatus, targetStatus);
        }
    }

    static class RepositoryControlConfiguration {

        @Bean
        @Primary
        ControllableCampaignRepository controllableCampaignRepository(
                @Qualifier("myBatisCampaignRepository") CampaignRepository delegate) {
            return new ControllableCampaignRepository(delegate);
        }
    }
}
