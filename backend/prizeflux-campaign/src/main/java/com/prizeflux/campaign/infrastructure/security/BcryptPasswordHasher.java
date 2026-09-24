package com.prizeflux.campaign.infrastructure.security;

import com.prizeflux.campaign.application.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** BCrypt 每次编码产生独立 Salt；不得把原口令或编码结果写入日志。 */
@Component
public class BcryptPasswordHasher implements PasswordHasher {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    @Override
    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 12 || rawPassword.length() > 128) {
            throw new IllegalArgumentException("Password length must be between 12 and 128 characters");
        }
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String storedHash) {
        return rawPassword != null && storedHash != null && encoder.matches(rawPassword, storedHash);
    }
}
