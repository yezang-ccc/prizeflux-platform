package com.prizeflux.campaign.infrastructure.random;

import com.prizeflux.campaign.application.ActorProvider;
import com.prizeflux.campaign.domain.random.RandomSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 运行时端口（身份、随机源、时钟）的装配；身份在持久化启用时由安全上下文适配器提供。 */
@Configuration
public class DrawRuntimeConfiguration {
    /** 仅轻量启动（持久化禁用、无认证过滤链）时占位；抽奖服务在该模式不加载，故不会真的走到这里。 */
    @Bean
    @ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "false")
    @ConditionalOnMissingBean(ActorProvider.class)
    ActorProvider unavailableActorProvider() {
        return () -> {
            throw new IllegalStateException("Authenticated actor provider is not configured");
        };
    }

    @Bean
    @ConditionalOnMissingBean(RandomSource.class)
    RandomSource randomSource() {
        return new ThreadLocalRandomSource();
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock clock() {
        return Clock.systemUTC();
    }
}
