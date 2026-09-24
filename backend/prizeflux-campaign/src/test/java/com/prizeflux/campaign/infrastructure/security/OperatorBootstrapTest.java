package com.prizeflux.campaign.infrastructure.security;

import com.prizeflux.campaign.application.AppUser;
import com.prizeflux.campaign.application.AppUserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorBootstrapTest {
    @Test
    void createsOnlyExplicitOperatorWithHashedPassword() throws Exception {
        RecordingUsers users = new RecordingUsers();
        new OperatorBootstrap(users, new BcryptPasswordHasher(), "operator.1", "test-only-password-123")
                .run(null);
        assertThat(users.role).isEqualTo(AppUser.Role.OPERATOR);
        assertThat(users.hash).startsWith("$2").doesNotContain("test-only-password-123");
    }

    @Test
    void refusesMissingCredentialsAndExistingAccount() {
        RecordingUsers users = new RecordingUsers();
        assertThatThrownBy(() -> new OperatorBootstrap(users, new BcryptPasswordHasher(), "", "")
                .run(null)).isInstanceOf(IllegalStateException.class);
        users.existing = true;
        assertThatThrownBy(() -> new OperatorBootstrap(users, new BcryptPasswordHasher(),
                "operator.1", "test-only-password-123").run(null))
                .isInstanceOf(IllegalStateException.class);
    }

    private static final class RecordingUsers implements AppUserRepository {
        private boolean existing;
        private String hash;
        private AppUser.Role role;

        @Override
        public Optional<AppUser> findByUsername(String username) {
            if (!existing) {
                return Optional.empty();
            }
            return Optional.of(new AppUser(1, username, "$2a$12$012345678901234567890u0123456789012345678901234567890",
                    AppUser.Role.OPERATOR, AppUser.State.ACTIVE));
        }

        @Override
        public long create(String username, String passwordHash, AppUser.Role role) {
            this.hash = passwordHash;
            this.role = role;
            return 1;
        }
    }
}
