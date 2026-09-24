package com.prizeflux.campaign.application;

/**
 * 事件传输端口：把 Outbox 事件投递到下游（D7-03 用 Kafka 实现，D7-06 用轮询适配器替代）。
 * 抛出异常表示本次投递失败，由 {@link OutboxPublisher} 决定重试或置为 DEAD。
 */
public interface DomainEventPublisher {
    void publish(OutboxEvent event) throws Exception;
}
