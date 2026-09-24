package com.prizeflux.campaign.infrastructure.security;

import com.prizeflux.campaign.application.AppUser;
import com.prizeflux.campaign.application.AppUserRepository;
import com.prizeflux.campaign.application.PasswordHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 只在显式启用的一次性启动中创建运营账号；无默认凭据，也不打印密码。 */
@Component
@ConditionalOnProperty(name = "prizeflux.bootstrap.operator.enabled", havingValue = "true")
public class OperatorBootstrap implements ApplicationRunner {
    private final AppUserRepository users;
    private final PasswordHasher passwords;
    private final String username;
    private final String rawPassword;

    public OperatorBootstrap(AppUserRepository users, PasswordHasher passwords,
            @Value("${PFX_BOOTSTRAP_OPERATOR_USERNAME:}") String username,
            @Value("${PFX_BOOTSTRAP_OPERATOR_PASSWORD:}") String rawPassword) {
        this.users = users;
        this.passwords = passwords;
        this.username = username;
        this.rawPassword = rawPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username == null || !username.matches("[a-z0-9._-]{3,64}")
                || rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalStateException("Operator bootstrap credentials are missing or invalid");
        }
        if (users.findByUsername(username).isPresent()) {
            throw new IllegalStateException("Operator account already exists; disable bootstrap after first run");
        }
        users.create(username, passwords.hash(rawPassword), AppUser.Role.OPERATOR);
    }
}
