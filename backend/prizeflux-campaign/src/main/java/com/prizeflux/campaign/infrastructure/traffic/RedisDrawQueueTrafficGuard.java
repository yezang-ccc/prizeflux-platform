package com.prizeflux.campaign.infrastructure.traffic;

import com.prizeflux.campaign.application.DrawQueueTrafficDecision;
import com.prizeflux.campaign.application.DrawQueueTrafficGuard;
import com.prizeflux.campaign.application.DrawQueueProtectionUnavailableException;
import com.prizeflux.campaign.application.DrawQueueProtectionUnavailableException.Reason;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/** Atomic multi-scope fixed windows; Redis is admission control only, never draw state. */
public final class RedisDrawQueueTrafficGuard implements DrawQueueTrafficGuard {
    private static final DefaultRedisScript<String> INCREMENT_SCOPES = new DefaultRedisScript<>("""
            local allowed = 1
            local retry = 0
            for index, key in ipairs(KEYS) do
              local count = redis.call('INCR', key)
              if count == 1 then redis.call('PEXPIRE', key, ARGV[1]) end
              local ttl = redis.call('PTTL', key)
              if ttl < 0 then return 'invalid' end
              if count > tonumber(ARGV[index + 1]) then
                allowed = 0
                if ttl > retry then retry = ttl end
              end
            end
            return tostring(allowed) .. ':' .. tostring(retry)
            """, String.class);

    private final StringRedisTemplate redis;
    private final DrawQueueProtectionProperties properties;

    public RedisDrawQueueTrafficGuard(StringRedisTemplate redis,
            DrawQueueProtectionProperties properties) {
        if (properties.getWindowSeconds() < 1 || properties.getUserMaxRequests() < 1
                || properties.getCampaignMaxRequests() < 1 || properties.getGlobalMaxRequests() < 1
                || properties.getStatusMaxRequests() < 1
                || properties.getKeyPrefix() == null
                || !properties.getKeyPrefix().matches("[A-Za-z0-9:_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid draw queue traffic configuration");
        }
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public DrawQueueTrafficDecision checkSubmission(long userId, long campaignId) {
        if (userId <= 0 || campaignId <= 0) throw new IllegalArgumentException("Invalid queue identity");
        String prefix = properties.getKeyPrefix() + "{draw-queue}:";
        return consume(List.of(prefix + "user:" + userId,
                        prefix + "campaign:" + campaignId, prefix + "global"),
                properties.getUserMaxRequests(), properties.getCampaignMaxRequests(),
                properties.getGlobalMaxRequests());
    }

    @Override
    public DrawQueueTrafficDecision checkStatusQuery(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("Authenticated user ID must be positive");
        String key = properties.getKeyPrefix() + "{draw-queue}:status:" + userId;
        return consume(List.of(key), properties.getStatusMaxRequests());
    }

    private DrawQueueTrafficDecision consume(List<String> keys, int... limits) {
        long ttlMillis = properties.getWindowSeconds() * 1000L;
        Object[] arguments = new Object[limits.length + 1];
        arguments[0] = Long.toString(ttlMillis);
        for (int index = 0; index < limits.length; index++) arguments[index + 1] = Integer.toString(limits[index]);
        String reply;
        try {
            reply = redis.execute(INCREMENT_SCOPES, keys, arguments);
        } catch (DataAccessException failure) {
            throw new DrawQueueProtectionUnavailableException(Reason.REDIS_UNAVAILABLE);
        }
        int separator = reply == null ? -1 : reply.indexOf(':');
        if (separator < 1 || separator == reply.length() - 1) {
            throw new DrawQueueProtectionUnavailableException(Reason.REDIS_INVALID_RESPONSE);
        }
        try {
            boolean allowed = "1".equals(reply.substring(0, separator));
            long ttl = Long.parseLong(reply.substring(separator + 1));
            if (ttl < 0 || (!allowed && ttl == 0)) throw new NumberFormatException("invalid retry window");
            return new DrawQueueTrafficDecision(allowed, allowed ? 0 : (ttl + 999) / 1000);
        } catch (NumberFormatException failure) {
            throw new DrawQueueProtectionUnavailableException(Reason.REDIS_INVALID_RESPONSE);
        }
    }
}
