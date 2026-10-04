package com.prizeflux.campaign.infrastructure.observability;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Profile("local-full")
@ConditionalOnProperty(name = {"prizeflux.kafka.enabled", "prizeflux.kafka.draw-queue-enabled",
        "prizeflux.kafka.draw-queue-consumer-enabled"}, havingValue = "true")
final class KafkaAdminDrawQueueLagReader implements DrawQueueKafkaLagReader {
    private static final long REQUEST_TIMEOUT_SECONDS = 5;
    private final AdminClient admin;
    private final String topic;
    private final String group;

    KafkaAdminDrawQueueLagReader(KafkaAdmin kafkaAdmin,
            @Value("${prizeflux.kafka.topic-draw-queue:prizeflux.draw.queued}") String topic,
            @Value("${prizeflux.kafka.group-draw-queue:prizeflux-campaign-draw-queue}") String group) {
        this.admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        this.topic = topic;
        this.group = group;
    }

    @Override
    public long readLag() throws Exception {
        var descriptions = admin.describeTopics(List.of(topic)).allTopicNames()
                .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        var description = descriptions.get(topic);
        if (description == null || description.partitions().isEmpty()) {
            throw new IllegalStateException("Draw queue topic has no partitions");
        }
        Map<TopicPartition, OffsetSpec> earliestRequests = new HashMap<>();
        Map<TopicPartition, OffsetSpec> latestRequests = new HashMap<>();
        for (var info : description.partitions()) {
            var partition = new TopicPartition(topic, info.partition());
            earliestRequests.put(partition, OffsetSpec.earliest());
            latestRequests.put(partition, OffsetSpec.latest());
        }
        var committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        var earliest = admin.listOffsets(earliestRequests).all().get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        var latest = admin.listOffsets(latestRequests).all().get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long lag = 0;
        for (var partition : latestRequests.keySet()) {
            var first = earliest.get(partition);
            var last = latest.get(partition);
            if (first == null || last == null) {
                throw new IllegalStateException("Kafka did not return draw queue offsets");
            }
            // The committed offset is the next record. The queue contract resets to earliest;
            // after retention, a stale commit below the log start cannot increase retained lag.
            var offset = committed.get(partition);
            long next = offset == null ? first.offset() : Math.max(first.offset(), offset.offset());
            lag = Math.addExact(lag, Math.max(0L, last.offset() - next));
        }
        return lag;
    }

    @PreDestroy
    void close() {
        admin.close(Duration.ofSeconds(2));
    }
}
