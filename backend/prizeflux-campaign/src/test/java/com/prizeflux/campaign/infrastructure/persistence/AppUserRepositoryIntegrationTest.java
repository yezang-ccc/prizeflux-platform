package com.prizeflux.campaign.infrastructure.persistence;

import com.prizeflux.campaign.PrizeFluxCampaignApplication;
import com.prizeflux.campaign.application.AppUser;
import com.prizeflux.campaign.application.AppUserRepository;
import com.prizeflux.campaign.application.UsernameAlreadyRegisteredException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实 MySQL 验证账号迁移、读写和数据库约束。 */
@Testcontainers
@SpringBootTest(classes = PrizeFluxCampaignApplication.class, properties = {
        "spring.autoconfigure.exclude=",
        "spring.main.web-application-type=none",
        "spring.flyway.enabled=true",
        "prizeflux.persistence.campaign.enabled=true",
        "PFX_JWT_SECRET=test-only-jwt-secret-0123456789abcdef0123456789"
})
@Transactional
class AppUserRepositoryIntegrationTest {
    private static final String FAKE_BCRYPT = "$2a$12$012345678901234567890u0123456789012345678901234567890";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("prizeflux")
            .withUsername("prizeflux")
            .withPassword("integration-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test
    void savesAccountAndEnforcesUniqueUsername() {
        long id = users.create("operator.1", FAKE_BCRYPT, AppUser.Role.OPERATOR);
        AppUser account = users.findByUsername("operator.1").orElseThrow();
        assertThat(account.id()).isEqualTo(id);
        assertThat(account.role()).isEqualTo(AppUser.Role.OPERATOR);
        assertThat(account.state()).isEqualTo(AppUser.State.ACTIVE);
        assertThat(account.passwordHash()).isEqualTo(FAKE_BCRYPT);
        assertThatThrownBy(() -> users.create("operator.1", FAKE_BCRYPT, AppUser.Role.USER))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseRejectsInvalidRoleAndPlaintextPassword() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_user (username, password_hash, role, state) VALUES (?, ?, ?, ?)",
                "invalid-role", FAKE_BCRYPT, "ADMIN", "ACTIVE"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_user (username, password_hash, role, state) VALUES (?, ?, ?, ?)",
                "plain-user", "plaintext", "USER", "ACTIVE"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void selfRegistrationAlwaysCreatesUserAndMapsUniqueConstraintConflict() {
        long userId = users.registerUser("self.signup", FAKE_BCRYPT);
        AppUser created = users.findByUsername("self.signup").orElseThrow();

        assertThat(created.id()).isEqualTo(userId);
        assertThat(created.role()).isEqualTo(AppUser.Role.USER);
        assertThat(created.state()).isEqualTo(AppUser.State.ACTIVE);
        assertThatThrownBy(() -> users.registerUser("self.signup", FAKE_BCRYPT))
                .isInstanceOf(UsernameAlreadyRegisteredException.class);
    }
}
