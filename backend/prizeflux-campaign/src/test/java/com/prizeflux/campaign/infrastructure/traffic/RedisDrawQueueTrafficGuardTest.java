package com.prizeflux.campaign.infrastructure.traffic;

import com.prizeflux.campaign.application.DrawQueueProtectionUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class RedisDrawQueueTrafficGuardTest {
    @Test
    void reportsRedisConnectionFailureAsAnInternalProtectionReason() {
        StringRedisTemplate redis = new StringRedisTemplate() {
            @Override
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                throw new RedisConnectionFailureException("test-only failure");
            }
        };
        DrawQueueProtectionProperties properties = new DrawQueueProtectionProperties();
        properties.setKeyPrefix("test:");
        RedisDrawQueueTrafficGuard guard = new RedisDrawQueueTrafficGuard(redis, properties);

        Throwable failure = catchThrowable(() -> guard.checkSubmission(1, 1));

        assertThat(failure).isInstanceOf(DrawQueueProtectionUnavailableException.class);
        assertThat(((DrawQueueProtectionUnavailableException) failure).reason())
                .isEqualTo(DrawQueueProtectionUnavailableException.Reason.REDIS_UNAVAILABLE);
    }

    @Test
    void doesNotRejectWhenNoRedisDecisionIsRequired() {
        DrawQueueProtectionProperties properties = new DrawQueueProtectionProperties();
        properties.setKeyPrefix("test:");
        RedisDrawQueueTrafficGuard guard = new RedisDrawQueueTrafficGuard(new StringRedisTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                return (T) "1:0";
            }
        }, properties);

        assertThat(guard.checkSubmission(1, 1).allowed()).isTrue();
    }
}
