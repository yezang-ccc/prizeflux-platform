package com.prizeflux.campaign.infrastructure.security;

import com.prizeflux.campaign.application.AppUser;
import com.prizeflux.campaign.application.AuthenticatedActor;
import com.prizeflux.campaign.application.InvalidTokenException;
import com.prizeflux.campaign.application.IssuedToken;
import com.prizeflux.campaign.application.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 基于 Spring Security JWT 的 {@link TokenService} 实现。
 * 令牌只含 sub/username/role/iat/exp 最小声明；过期默认 15 分钟且不允许超过 1 小时。
 * 失败路径不记录令牌内容，避免把凭据写进日志。
 */
@Component
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class JwtTokenService implements TokenService {

    private static final Duration MAX_TTL = Duration.ofHours(1);

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Clock clock;
    private final Duration ttl;

    public JwtTokenService(JwtEncoder encoder, JwtDecoder decoder, Clock clock,
            @Value("${prizeflux.security.jwt.ttl:PT15M}") Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalStateException("JWT ttl must be positive and at most 1 hour");
        }
        this.encoder = encoder;
        this.decoder = decoder;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public IssuedToken issue(AppUser user) {
        Objects.requireNonNull(user, "user");
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(Long.toString(user.id()))
                .claim("username", user.username())
                .claim("role", user.role().name())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, expiresAt);
    }

    @Override
    public AuthenticatedActor verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidTokenException();
        }
        try {
            return toActor(decoder.decode(rawToken));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            throw new InvalidTokenException();
        }
    }

    /** 从已通过签名与时效校验的 Jwt 提取最小身份；供资源服务器转换器复用同一映射。 */
    static AuthenticatedActor toActor(Jwt jwt) {
        long userId = Long.parseLong(Objects.requireNonNull(jwt.getSubject()));
        String username = Objects.requireNonNull(jwt.getClaimAsString("username"));
        AppUser.Role role = AppUser.Role.valueOf(
                Objects.requireNonNull(jwt.getClaimAsString("role")));
        return new AuthenticatedActor(userId, username, role);
    }
}
