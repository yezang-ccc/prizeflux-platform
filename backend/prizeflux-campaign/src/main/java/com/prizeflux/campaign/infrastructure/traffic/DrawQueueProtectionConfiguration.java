package com.prizeflux.campaign.infrastructure.traffic;

import com.prizeflux.campaign.application.DrawQueueCapacityGuard;
import com.prizeflux.campaign.application.DrawQueueTrafficDecision;
import com.prizeflux.campaign.application.DrawQueueTrafficGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(DrawQueueProtectionProperties.class)
public class DrawQueueProtectionConfiguration {
    @Bean
    @ConditionalOnProperty(name = "prizeflux.traffic.draw-queue.enabled", havingValue = "true")
    DrawQueueTrafficGuard redisDrawQueueTrafficGuard(StringRedisTemplate redis,
            DrawQueueProtectionProperties properties) {
        return new RedisDrawQueueTrafficGuard(redis, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "prizeflux.traffic.draw-queue.enabled", havingValue = "false",
            matchIfMissing = true)
    DrawQueueTrafficGuard allowDrawQueueTrafficGuard() {
        return new DrawQueueTrafficGuard() {
            @Override
            public DrawQueueTrafficDecision checkSubmission(long userId, long campaignId) {
                return DrawQueueTrafficDecision.allow();
            }

            @Override
            public DrawQueueTrafficDecision checkStatusQuery(long userId) {
                return DrawQueueTrafficDecision.allow();
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "prizeflux.traffic.draw-queue.enabled", havingValue = "true")
    DrawQueueCapacityGuard jdbcDrawQueueCapacityGuard(
            javax.sql.DataSource dataSource, DrawQueueProtectionProperties properties) {
        return new JdbcDrawQueueCapacityGuard(dataSource, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "prizeflux.traffic.draw-queue.enabled", havingValue = "false",
            matchIfMissing = true)
    DrawQueueCapacityGuard allowDrawQueueCapacityGuard() {
        return pressure -> { };
    }
}
