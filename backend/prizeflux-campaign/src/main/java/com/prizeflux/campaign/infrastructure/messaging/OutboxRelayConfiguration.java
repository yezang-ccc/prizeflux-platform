package com.prizeflux.campaign.infrastructure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 仅当 Outbox Relay 启用时开启调度；未启用时保持纯 HTTP 的轻量上下文，不注册任何定时任务。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "prizeflux.outbox.relay.enabled", havingValue = "true")
public class OutboxRelayConfiguration {
}
