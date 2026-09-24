package com.prizeflux.campaign.infrastructure.security;

import com.prizeflux.campaign.application.AppUser;
import com.prizeflux.campaign.application.AppUserRepository;
import com.prizeflux.campaign.application.PasswordHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 仅在显式启用的一次性启动中创建演示 {@code USER} 账号，供独立进程冒烟与手动抽奖使用。
 * 与 {@link OperatorBootstrap} 的差异：账号已存在时直接跳过（幂等），便于反复冒烟；
 * 运营账号是特权账号，故保留"已存在即失败"的强约束。无默认凭据，也不打印密码。
 */
@Component
@ConditionalOnProperty(name = "prizeflux.bootstrap.demo-user.enabled", havingValue = "true")
public class DemoUserBootstrap implements ApplicationRunner {
    private final AppUserRepository users;
    private final PasswordHasher passwords;
    private final String username;
    private final String rawPassword;

    public DemoUserBootstrap(AppUserRepository users, PasswordHasher passwords,
            @Value("${PFX_BOOTSTRAP_DEMO_USERNAME:}") String username,
            @Value("${PFX_BOOTSTRAP_DEMO_PASSWORD:}") String rawPassword) {
        this.users = users;
        this.passwords = passwords;
        this.username = username;
        this.rawPassword = rawPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username == null || !username.matches("[a-z0-9._-]{3,64}")
                || rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalStateException("Demo user bootstrap credentials are missing or invalid");
        }
        if (users.findByUsername(username).isPresent()) {
            return; // 幂等：已存在则跳过，便于重复冒烟。
        }
        users.create(username, passwords.hash(rawPassword), AppUser.Role.USER);
    }
}
