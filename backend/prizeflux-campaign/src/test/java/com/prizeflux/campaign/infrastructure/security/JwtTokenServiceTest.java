package com.prizeflux.campaign.infrastructure.security;

import com.prizeflux.campaign.application.AppUser;
import com.prizeflux.campaign.application.AuthenticatedActor;
import com.prizeflux.campaign.application.InvalidTokenException;
import com.prizeflux.campaign.application.IssuedToken;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 固定时钟下的签发/过期/篡改/密钥边界测试；不启动 Spring 容器。 */
class JwtTokenServiceTest {

    private static final String SECRET = "test-only-jwt-secret-0123456789abcdef0123456789";
    private static final String OTHER_SECRET = "other-test-jwt-secret-0123456789abcdef0123456";
    private static final Instant BASE = Instant.parse("2026-09-24T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final String FAKE_BCRYPT =
            "$2a$12$012345678901234567890u0123456789012345678901234567890";

    private final SecretKey key = JwtSecurityConfiguration.hmacKey(SECRET);

    @Test
    void issuedTokenCarriesMinimalIdentityAndExpiresAfterTtl() {
        JwtTokenService service = serviceAt(BASE, key);
        AppUser user = new AppUser(7, "user.1", FAKE_BCRYPT, AppUser.Role.USER, AppUser.State.ACTIVE);

        IssuedToken token = service.issue(user);

        assertThat(token.expiresAt()).isEqualTo(BASE.plus(TTL));
        AuthenticatedActor actor = service.verify(token.value());
        assertThat(actor).isEqualTo(new AuthenticatedActor(7, "user.1", AppUser.Role.USER));

        // 载荷只含最小身份声明，绝不泄漏密码哈希。
        String payload = new String(
                Base64.getUrlDecoder().decode(token.value().split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"sub\":\"7\"").contains("\"role\":\"USER\"")
                .doesNotContain("password").doesNotContain("$2");
    }

    @Test
    void expiredTokenIsRejectedAtFixedClock() {
        JwtTokenService issuing = serviceAt(BASE, key);
        IssuedToken token = issuing.issue(
                new AppUser(7, "user.1", FAKE_BCRYPT, AppUser.Role.USER, AppUser.State.ACTIVE));

        // 有效期内可验证；超过 TTL 后同一令牌被拒绝。
        serviceAt(BASE.plus(TTL).minusSeconds(1), key).verify(token.value());
        assertThatThrownBy(() -> serviceAt(BASE.plus(TTL).plusSeconds(1), key).verify(token.value()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Invalid token");
    }

    @Test
    void tamperedOrForgedTokenIsRejected() {
        JwtTokenService service = serviceAt(BASE, key);
        IssuedToken token = service.issue(
                new AppUser(7, "user.1", FAKE_BCRYPT, AppUser.Role.USER, AppUser.State.ACTIVE));

        // 改动载荷字符导致签名不匹配。
        String[] parts = token.value().split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[0] = payload[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];
        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(InvalidTokenException.class);

        // 其他密钥签发的令牌（伪造）被拒绝。
        JwtTokenService forger = serviceAt(BASE, JwtSecurityConfiguration.hmacKey(OTHER_SECRET));
        IssuedToken forged = forger.issue(
                new AppUser(7, "user.1", FAKE_BCRYPT, AppUser.Role.OPERATOR, AppUser.State.ACTIVE));
        assertThatThrownBy(() -> service.verify(forged.value()))
                .isInstanceOf(InvalidTokenException.class);

        // 缺失或空白令牌同样失败。
        assertThatThrownBy(() -> service.verify(null)).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service.verify(" ")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void missingOrShortSecretRefusesToStart() {
        assertThatThrownBy(() -> JwtSecurityConfiguration.hmacKey(null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtSecurityConfiguration.hmacKey(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtSecurityConfiguration.hmacKey("short-secret"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(JwtSecurityConfiguration.hmacKey(SECRET).getEncoded())
                .hasSizeGreaterThanOrEqualTo(JwtSecurityConfiguration.MIN_SECRET_BYTES);
    }

    @Test
    void ttlMustBeShortLived() {
        JwtEncoder encoder = JwtSecurityConfiguration.hmacEncoder(key);
        JwtDecoder decoder = JwtSecurityConfiguration.hmacDecoder(key, Clock.fixed(BASE, ZoneOffset.UTC));
        assertThatThrownBy(() -> new JwtTokenService(encoder, decoder,
                Clock.fixed(BASE, ZoneOffset.UTC), Duration.ofDays(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    private JwtTokenService serviceAt(Instant now, SecretKey secretKey) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new JwtTokenService(
                JwtSecurityConfiguration.hmacEncoder(secretKey),
                JwtSecurityConfiguration.hmacDecoder(secretKey, clock),
                clock, TTL);
    }
}
