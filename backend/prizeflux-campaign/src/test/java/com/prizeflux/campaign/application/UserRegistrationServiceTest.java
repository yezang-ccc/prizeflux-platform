package com.prizeflux.campaign.application;

import com.prizeflux.campaign.infrastructure.security.BcryptPasswordHasher;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRegistrationServiceTest {
    private final PasswordHasher passwords = new BcryptPasswordHasher();

    @Test
    void createsHashedUserUsingServerAssignedRoleAndSafeResult() {
        AtomicReference<AppUser> stored = new AtomicReference<>();
        AppUserRepository users = repository(stored);

        UserRegistrationService.RegisteredUser result = new UserRegistrationService(users, passwords)
                .register("  New.User  ", "test-only-password-123");

        assertThat(result.userId()).isEqualTo(42);
        assertThat(result.username()).isEqualTo("new.user");
        assertThat(stored.get().username()).isEqualTo("new.user");
        assertThat(stored.get().role()).isEqualTo(AppUser.Role.USER);
        assertThat(stored.get().passwordHash()).startsWith("$2").doesNotContain("test-only-password-123");
        assertThat(passwords.matches("test-only-password-123", stored.get().passwordHash())).isTrue();
    }

    @Test
    void rejectsInvalidUsernameAndBcryptOversizePasswordBeforePersistence() {
        AtomicReference<AppUser> stored = new AtomicReference<>();
        UserRegistrationService service = new UserRegistrationService(repository(stored), passwords);

        assertInvalid(() -> service.register("bad name", "test-only-password-123"));
        assertInvalid(() -> service.register("new.user", "short"));
        assertInvalid(() -> service.register("new.user", "汉".repeat(25)));
        assertThat(stored.get()).isNull();
    }

    @Test
    void reportsTakenUsernameIncludingRepositoryRace() {
        AppUserRepository users = new AppUserRepository() {
            @Override public Optional<AppUser> findByUsername(String username) { return Optional.empty(); }
            @Override public long create(String username, String hash, AppUser.Role role) {
                throw new UnsupportedOperationException();
            }
            @Override public long registerUser(String username, String hash) {
                throw new UsernameAlreadyRegisteredException();
            }
        };

        assertThatThrownBy(() -> new UserRegistrationService(users, passwords)
                .register("taken.user", "test-only-password-123"))
                .isInstanceOf(UsernameAlreadyRegisteredException.class);
    }

    private AppUserRepository repository(AtomicReference<AppUser> stored) {
        return new AppUserRepository() {
            @Override public Optional<AppUser> findByUsername(String username) {
                AppUser user = stored.get();
                return user != null && user.username().equals(username) ? Optional.of(user) : Optional.empty();
            }
            @Override public long create(String username, String hash, AppUser.Role role) {
                throw new UnsupportedOperationException();
            }
            @Override public long registerUser(String username, String hash) {
                stored.set(new AppUser(42, username, hash, AppUser.Role.USER, AppUser.State.ACTIVE));
                return 42;
            }
        };
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .satisfies(failure -> assertThat(((BusinessException) failure).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ARGUMENT));
    }
}
