package com.prizeflux.worker.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on durable retry polling only in an explicitly configured Worker runtime. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = {"prizeflux.worker.retry.enabled", "prizeflux.worker.retry.scheduler-enabled"},
        havingValue = "true")
public class FulfillmentRetrySchedulingConfiguration {
}
