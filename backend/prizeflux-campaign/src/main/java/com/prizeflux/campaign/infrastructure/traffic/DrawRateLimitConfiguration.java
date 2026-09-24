package com.prizeflux.campaign.infrastructure.traffic;

import com.prizeflux.campaign.application.DrawRateLimitDecision;
import com.prizeflux.campaign.application.DrawRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 按环境显式选择 Redis 限流或轻量禁用适配器。 */
@Configuration
@EnableConfigurationProperties(DrawRateLimitProperties.class)
public class DrawRateLimitConfiguration {
    @Bean
    @ConditionalOnProperty(name = "prizeflux.traffic.draw-rate-limit.enabled", havingValue = "true")
    DrawRateLimiter redisDrawRateLimiter(StringRedisTemplate redis, DrawRateLimitProperties properties) {
        return new RedisDrawRateLimiter(redis, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "prizeflux.traffic.draw-rate-limit.enabled",
            havingValue = "false", matchIfMissing = true)
    DrawRateLimiter disabledDrawRateLimiter() {
        return userId -> DrawRateLimitDecision.unlimited();
    }
}
