package com.prizeflux.campaign.application;

import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Locale;

/** 验证账号状态与口令；签发令牌属于后续 I3。 */
@Service
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class UserAuthenticationService {
    private final AppUserRepository users;
    private final PasswordHasher passwords;

    public UserAuthenticationService(AppUserRepository users, PasswordHasher passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    public AppUser authenticate(String username, String rawPassword) {
        String canonicalUsername = username == null ? null : username.trim().toLowerCase(Locale.ROOT);
        if (canonicalUsername == null || !canonicalUsername.matches("[a-z0-9._-]{3,64}")
                || rawPassword == null) {
            throw new InvalidCredentialsException();
        }
        AppUser user = users.findByUsername(canonicalUsername).orElseThrow(InvalidCredentialsException::new);
        if (user.state() != AppUser.State.ACTIVE
                || !passwords.matches(rawPassword, user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return user;
    }
}
