package com.prizeflux.worker.infrastructure.config;

import com.prizeflux.worker.application.FulfillmentRetryPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Worker 运行时基础配置：注入 UTC 时钟，供消费用例取一致时间戳；测试可用固定时钟覆盖。 */
@Configuration
public class WorkerRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FulfillmentRetryPolicy fulfillmentRetryPolicy() {
        return new FulfillmentRetryPolicy();
    }
}
