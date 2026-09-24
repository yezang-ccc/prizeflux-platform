package com.prizeflux.campaign.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

/**
 * JWT 签发/校验的基础设施装配。签名密钥只来自环境变量 {@code PFX_JWT_SECRET}，
 * 不提供任何默认值：缺失或过短时直接拒绝启动，而不是退回可猜测的密钥。
 * 与持久化开关联动，数据库禁用的健康检查启动模式不要求密钥。
 */
@Configuration
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class JwtSecurityConfiguration {

    /** HS256 密钥至少 256 位；这是底层算法要求，不是业务策略。 */
    static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecretKey jwtSecretKey(@Value("${PFX_JWT_SECRET:}") String secret) {
        return hmacKey(secret);
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return hmacEncoder(jwtSecretKey);
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, Clock clock) {
        return hmacDecoder(jwtSecretKey, clock);
    }

    static SecretKey hmacKey(String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "PFX_JWT_SECRET must be provided with at least 32 bytes; no default key exists");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    static JwtEncoder hmacEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    static JwtDecoder hmacDecoder(SecretKey key, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(Duration.ZERO);
        timestampValidator.setClock(clock);
        decoder.setJwtValidator(timestampValidator);
        return decoder;
    }
}
