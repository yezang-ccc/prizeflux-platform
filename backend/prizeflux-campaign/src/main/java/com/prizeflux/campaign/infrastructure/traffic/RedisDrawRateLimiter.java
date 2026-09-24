package com.prizeflux.campaign.infrastructure.traffic;

import com.prizeflux.campaign.application.DrawRateLimitDecision;
import com.prizeflux.campaign.application.DrawRateLimiter;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/** 多实例共享的 Redis 固定窗口计数器；它只限制流量，不参与抽奖事务或库存决策。 */
public final class RedisDrawRateLimiter implements DrawRateLimiter {
    private static final DefaultRedisScript<String> INCREMENT_WINDOW = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            return tostring(count) .. ':' .. tostring(ttl)
            """, String.class);

    private final StringRedisTemplate redis;
    private final DrawRateLimitProperties properties;

    public RedisDrawRateLimiter(StringRedisTemplate redis,
            DrawRateLimitProperties properties) {
        if (properties.getMaxRequests() < 1 || properties.getWindowSeconds() < 1
                || properties.getKeyPrefix() == null
                || !properties.getKeyPrefix().matches("[A-Za-z0-9:_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid draw rate-limit configuration");
        }
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public DrawRateLimitDecision consume(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("Authenticated user ID must be positive");
        }
        String key = properties.getKeyPrefix() + "{" + userId + "}";
        long ttlMillis = properties.getWindowSeconds() * 1000L;
        String reply;
        try {
            reply = redis.execute(INCREMENT_WINDOW, List.of(key), Long.toString(ttlMillis));
        } catch (DataAccessException exception) {
            throw new DrawRateLimitUnavailableException();
        }
        int separator = reply == null ? -1 : reply.indexOf(':');
        if (separator < 1 || separator == reply.length() - 1) {
            throw new DrawRateLimitUnavailableException();
        }
        long count;
        long ttlRemainingMillis;
        try {
            count = Long.parseLong(reply.substring(0, separator));
            ttlRemainingMillis = Long.parseLong(reply.substring(separator + 1));
        } catch (NumberFormatException exception) {
            throw new DrawRateLimitUnavailableException();
        }
        if (count < 1 || ttlRemainingMillis < 0) {
            throw new DrawRateLimitUnavailableException();
        }
        int limit = properties.getMaxRequests();
        boolean allowed = count <= limit;
        int remaining = allowed ? limit - (int) count : 0;
        long retryAfterSeconds = allowed ? 0 : (ttlRemainingMillis + 999) / 1000;
        return new DrawRateLimitDecision(allowed, limit, remaining, retryAfterSeconds);
    }
}
