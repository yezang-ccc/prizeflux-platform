package com.prizeflux.campaign.infrastructure.traffic;

import com.prizeflux.campaign.application.DrawRateLimitDecision;
import com.prizeflux.campaign.application.DrawRateLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 在真实 Redis 上验证多实例共享计数器的原子上限，而不触碰 MySQL 库存。 */
@Testcontainers
class RedisDrawRateLimiterIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connectToRedisContainer() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void closeRedisClient() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void concurrentRequestsAcrossTwoInstancesCannotExceedTheSharedWindowLimit() throws Exception {
        DrawRateLimitProperties properties = properties(17);
        DrawRateLimiter firstInstance = new RedisDrawRateLimiter(redis, properties);
        DrawRateLimiter secondInstance = new RedisDrawRateLimiter(redis, properties);
        int attempts = 80;
        CountDownLatch ready = new CountDownLatch(16);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Future<DrawRateLimitDecision>> results = new ArrayList<>();

        try {
            for (int index = 0; index < attempts; index++) {
                DrawRateLimiter instance = index % 2 == 0 ? firstInstance : secondInstance;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return instance.consume(42);
                }));
            }
            ready.await();
            start.countDown();
            List<DrawRateLimitDecision> decisions = new ArrayList<>();
            for (Future<DrawRateLimitDecision> result : results) {
                decisions.add(result.get());
            }

            assertThat(decisions).filteredOn(DrawRateLimitDecision::allowed).hasSize(17);
            assertThat(decisions).filteredOn(decision -> !decision.allowed()).hasSize(attempts - 17);
            assertThat(decisions).filteredOn(decision -> !decision.allowed())
                    .allMatch(decision -> decision.remaining() == 0 && decision.retryAfterSeconds() > 0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void usersAreIsolatedAndRedisOwnsWindowExpiry() throws InterruptedException {
        DrawRateLimitProperties properties = properties(1);
        properties.setWindowSeconds(1);
        DrawRateLimiter limiter = new RedisDrawRateLimiter(redis, properties);

        assertThat(limiter.consume(100).allowed()).isTrue();
        assertThat(limiter.consume(100).allowed()).isFalse();
        assertThat(limiter.consume(101).allowed()).isTrue();
        Thread.sleep(1500);
        assertThat(limiter.consume(100).allowed()).isTrue();
    }

    private static DrawRateLimitProperties properties(int maxRequests) {
        DrawRateLimitProperties properties = new DrawRateLimitProperties();
        properties.setMaxRequests(maxRequests);
        properties.setWindowSeconds(60);
        properties.setKeyPrefix("test:" + UUID.randomUUID() + ":");
        return properties;
    }
}
