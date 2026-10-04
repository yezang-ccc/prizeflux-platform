package com.prizeflux.campaign.infrastructure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Queue lease heartbeats and MySQL recovery scanning are enabled only in an executor process. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "prizeflux.kafka.draw-queue-enabled", havingValue = "true")
public class DrawQueueRecoveryConfiguration {
    @Bean(name = {"drawQueueTaskScheduler", "taskScheduler"})
    ThreadPoolTaskScheduler drawQueueTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("draw-queue-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
