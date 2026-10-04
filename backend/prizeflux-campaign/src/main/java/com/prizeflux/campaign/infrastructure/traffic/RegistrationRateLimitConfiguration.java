package com.prizeflux.campaign.infrastructure.traffic;

import com.prizeflux.campaign.application.RegistrationRateLimitDecision;
import com.prizeflux.campaign.application.RegistrationRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 按部署配置选择共享 Redis 限流或显式禁用适配器。 */
@Configuration
@EnableConfigurationProperties(RegistrationRateLimitProperties.class)
public class RegistrationRateLimitConfiguration {
    @Bean
    @ConditionalOnProperty(name = "prizeflux.traffic.registration-rate-limit.enabled", havingValue = "true")
    RegistrationRateLimiter redisRegistrationRateLimiter(
            StringRedisTemplate redis, RegistrationRateLimitProperties properties) {
        return new RedisRegistrationRateLimiter(redis, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "prizeflux.traffic.registration-rate-limit.enabled",
            havingValue = "false", matchIfMissing = true)
    RegistrationRateLimiter disabledRegistrationRateLimiter() {
        return remoteAddress -> RegistrationRateLimitDecision.unlimited();
    }
}
