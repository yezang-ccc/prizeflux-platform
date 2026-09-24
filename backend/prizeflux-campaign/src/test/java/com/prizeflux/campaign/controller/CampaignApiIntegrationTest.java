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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        return mockMvc.perform(request.header("Authorization", "Bearer " + operatorToken));
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
