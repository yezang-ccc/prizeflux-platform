package com.prizeflux.campaign.infrastructure.traffic;

import com.prizeflux.campaign.application.RegistrationRateLimitDecision;
import com.prizeflux.campaign.application.RegistrationRateLimitUnavailableException;
import com.prizeflux.campaign.application.RegistrationRateLimiter;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 多 Campaign 实例共享的 Redis 固定窗口注册限流器；Redis 故障时由调用方拒绝请求。 */
public final class RedisRegistrationRateLimiter implements RegistrationRateLimiter {
    private static final DefaultRedisScript<String> INCREMENT_WINDOW = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            return tostring(count) .. ':' .. tostring(ttl)
            """, String.class);

    private final StringRedisTemplate redis;
    private final RegistrationRateLimitProperties properties;

    public RedisRegistrationRateLimiter(StringRedisTemplate redis,
            RegistrationRateLimitProperties properties) {
        if (properties.getMaxRequests() < 1 || properties.getWindowSeconds() < 1
                || properties.getKeyPrefix() == null
                || !properties.getKeyPrefix().matches("[A-Za-z0-9:_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid registration rate-limit configuration");
        }
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public RegistrationRateLimitDecision consume(String remoteAddress) {
        String key = properties.getKeyPrefix() + "{" + addressDigest(remoteAddress) + "}";
        long ttlMillis = properties.getWindowSeconds() * 1000L;
        String reply;
        try {
            reply = redis.execute(INCREMENT_WINDOW, List.of(key), Long.toString(ttlMillis));
        } catch (DataAccessException exception) {
            throw new RegistrationRateLimitUnavailableException();
        }
        int separator = reply == null ? -1 : reply.indexOf(':');
        if (separator < 1 || separator == reply.length() - 1) {
            throw new RegistrationRateLimitUnavailableException();
        }
        long count;
        long ttlRemainingMillis;
        try {
            count = Long.parseLong(reply.substring(0, separator));
            ttlRemainingMillis = Long.parseLong(reply.substring(separator + 1));
        } catch (NumberFormatException exception) {
            throw new RegistrationRateLimitUnavailableException();
        }
        if (count < 1 || ttlRemainingMillis < 0) {
            throw new RegistrationRateLimitUnavailableException();
        }

        int limit = properties.getMaxRequests();
        boolean allowed = count <= limit;
        int remaining = allowed ? limit - (int) count : 0;
        long retryAfterSeconds = allowed ? 0 : (ttlRemainingMillis + 999) / 1000;
        return new RegistrationRateLimitDecision(allowed, limit, remaining, retryAfterSeconds);
    }

    private static String addressDigest(String remoteAddress) {
        String address = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(address.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
