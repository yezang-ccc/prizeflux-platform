package com.prizeflux.campaign.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.PrizeFluxCampaignApplication;
import com.prizeflux.campaign.application.AppUser;
import com.prizeflux.campaign.application.AppUserRepository;
import com.prizeflux.campaign.application.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 MySQL + MockMvc 验证登录、当前用户、缺失/篡改令牌与伪造身份头被忽略。 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = PrizeFluxCampaignApplication.class,
        properties = {
                "spring.autoconfigure.exclude=",
                "spring.flyway.enabled=true",
                "prizeflux.persistence.campaign.enabled=true",
                "prizeflux.traffic.draw-rate-limit.enabled=true",
                "prizeflux.traffic.draw-rate-limit.max-requests=1",
                "prizeflux.traffic.draw-rate-limit.window-seconds=60",
                "PFX_JWT_SECRET=test-only-jwt-secret-0123456789abcdef0123456789"
        })
@AutoConfigureMockMvc
class AuthenticationIntegrationTest {

    private static final String PASSWORD = "test-only-password-123";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("prizeflux")
            .withUsername("prizeflux")
            .withPassword("integration-only");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET time_zone = '+00:00'");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository users;
    @Autowired PasswordHasher passwords;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetAccounts() {
        jdbc.update("DELETE FROM app_user");
        users.create("operator.1", passwords.hash(PASSWORD), AppUser.Role.OPERATOR);
        users.create("user.1", passwords.hash(PASSWORD), AppUser.Role.USER);
    }

    @Test
    void loginReturnsTokenAndExpiry() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("operator.1", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PFX-0000"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());
    }

    @Test
    void loginFailuresShareSinglePublicError() throws Exception {
        for (Map<String, String> body : List.of(
                Map.of("username", "user.1", "password", "wrong-password-123"),
                Map.of("username", "missing.1", "password", PASSWORD))) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("PFX-1002"))
                    .andExpect(jsonPath("$.message").value("未认证"));
        }
    }

    @Test
    void meReturnsServerIdentityAndIgnoresForgedHeader() throws Exception {
        String token = login("user.1");
        long expectedId = users.findByUsername("user.1").orElseThrow().id();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token)
                        .header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(expectedId))
                .andExpect(jsonPath("$.data.username").value("user.1"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void meWithoutOrTamperedTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PFX-1002"));

        String token = login("user.1");
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[0] = payload[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PFX-1002"));
    }

    @Test
    void anonymousAndWrongRoleCannotEnterProtectedWriteEndpoints() throws Exception {
        mockMvc.perform(post("/api/campaigns").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PFX-1002"));
        mockMvc.perform(post("/api/draws").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PFX-1002"));

        String userToken = login("user.1");
        mockMvc.perform(post("/api/campaigns")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PFX-1003"));
        mockMvc.perform(post("/api/campaigns/100/configuration")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PFX-1003"));

        String operatorToken = login("operator.1");
        mockMvc.perform(post("/api/draws")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PFX-1003"));
    }

    @Test
    void authenticatedUserDrawRequestsShareRedisLimitAfterJwtAuthentication() throws Exception {
        String userToken = login("user.1");
        String body = "{\"campaignId\":999999,\"requestKey\":\"rate-limit-1\"}";

        mockMvc.perform(post("/api/draws")
                        .header("Authorization", "Bearer " + userToken)
                        .header("X-User-Id", "999999")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/draws")
                        .header("Authorization", "Bearer " + userToken)
                        .header("X-User-Id", "888888")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PFX-3001"))
                .andExpect(jsonPath("$.message").value("请求过于频繁"))
                .andExpect(header().string("X-RateLimit-Limit", "1"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"));
    }

    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String loginBody(String username, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return objectMapper.writeValueAsString(body);
    }
}
