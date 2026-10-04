package com.prizeflux.campaign.application;

import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 公开自助注册用例；角色由服务端固定为 USER，运营账号仍经受控初始化创建。 */
@Service
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class UserRegistrationService {
    private static final int MIN_PASSWORD_CODE_POINTS = 12;
    private static final int MAX_BCRYPT_BYTES = 72;
    private static final String USERNAME_PATTERN = "[a-z0-9._-]{3,64}";

    private final AppUserRepository users;
    private final PasswordHasher passwords;

    public UserRegistrationService(AppUserRepository users, PasswordHasher passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    public RegisteredUser register(String username, String rawPassword) {
        String canonicalUsername = username == null ? null : username.trim().toLowerCase(Locale.ROOT);
        if (canonicalUsername == null || !canonicalUsername.matches(USERNAME_PATTERN)
                || !validPassword(rawPassword)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        if (users.findByUsername(canonicalUsername).isPresent()) {
            throw new UsernameAlreadyRegisteredException();
        }

        String passwordHash = passwords.hash(rawPassword);
        long userId = users.registerUser(canonicalUsername, passwordHash);
        return new RegisteredUser(userId, canonicalUsername);
    }

    private boolean validPassword(String password) {
        return password != null
                && password.codePointCount(0, password.length()) >= MIN_PASSWORD_CODE_POINTS
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BCRYPT_BYTES;
    }

    /** 不包含口令或哈希的注册结果。 */
    public record RegisteredUser(long userId, String username) { }
}
