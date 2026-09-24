package com.prizeflux.campaign.application;

import com.prizeflux.campaign.infrastructure.security.BcryptPasswordHasher;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAuthenticationServiceTest {
    private final PasswordHasher passwords = new BcryptPasswordHasher();
    private final String password = "test-only-password-123";
    private final String hash = passwords.hash(password);

    @Test
    void bcryptUsesDifferentSaltAndMatchesOnlyCorrectPassword() {
        assertThat(passwords.hash(password)).isNotEqualTo(hash);
        assertThat(passwords.matches(password, hash)).isTrue();
        assertThat(passwords.matches("wrong-password", hash)).isFalse();
        assertThatThrownBy(() -> passwords.hash("short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingWrongAndDisabledAccountsHaveSamePublicFailure() {
        UserAuthenticationService missing = new UserAuthenticationService(
                repository(Optional.empty()), passwords);
        UserAuthenticationService active = new UserAuthenticationService(
                repository(Optional.of(user(AppUser.State.ACTIVE))), passwords);
        UserAuthenticationService disabled = new UserAuthenticationService(
                repository(Optional.of(user(AppUser.State.DISABLED))), passwords);

        assertThat(active.authenticate("user.1", password).id()).isEqualTo(1);
        for (Runnable failed : new Runnable[] {
                () -> missing.authenticate("user.1", password),
                () -> active.authenticate("user.1", "wrong-password"),
                () -> disabled.authenticate("user.1", password)
        }) {
            assertThatThrownBy(failed::run).isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid credentials");
        }
    }

    private AppUser user(AppUser.State state) {
        return new AppUser(1, "user.1", hash, AppUser.Role.USER, state);
    }

    private static AppUserRepository repository(Optional<AppUser> account) {
        return new AppUserRepository() {
            @Override public Optional<AppUser> findByUsername(String username) { return account; }
            @Override public long create(String username, String passwordHash, AppUser.Role role) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
