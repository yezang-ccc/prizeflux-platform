package com.prizeflux.worker.infrastructure.observability;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Low-frequency Admin API reader. It uses broker offsets rather than scrape-time consumer polling. */
@Component
@Profile("local-full")
final class KafkaAdminConsumerLagReader implements KafkaConsumerLagReader {
    private static final long REQUEST_TIMEOUT_SECONDS = 5;

    private final AdminClient adminClient;
    private final String topic;
    private final String groupId;

    KafkaAdminConsumerLagReader(KafkaAdmin kafkaAdmin,
            @Value("${prizeflux.kafka.topic-fulfillment:prizeflux.fulfillment.requested}") String topic,
            @Value("${prizeflux.kafka.group-id:prizeflux-worker}") String groupId) {
        this.adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        this.topic = topic;
        this.groupId = groupId;
    }

    @Override
    public long readLag() throws Exception {
        var descriptions = adminClient.describeTopics(List.of(topic)).allTopicNames()
                .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        var description = descriptions.get(topic);
        if (description == null || description.partitions().isEmpty()) {
            throw new IllegalStateException("Configured Kafka topic has no partitions");
        }

        Map<TopicPartition, OffsetSpec> earliestRequests = new HashMap<>();
        Map<TopicPartition, OffsetSpec> latestRequests = new HashMap<>();
        for (var partitionInfo : description.partitions()) {
            var partition = new TopicPartition(topic, partitionInfo.partition());
            earliestRequests.put(partition, OffsetSpec.earliest());
            latestRequests.put(partition, OffsetSpec.latest());
        }

        var committed = adminClient.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata()
                .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        var earliest = adminClient.listOffsets(earliestRequests).all()
                .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        var latest = adminClient.listOffsets(latestRequests).all()
                .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        long totalLag = 0;
        for (var partition : latestRequests.keySet()) {
            var latestInfo = latest.get(partition);
            var earliestInfo = earliest.get(partition);
            if (latestInfo == null || earliestInfo == null) {
                throw new IllegalStateException("Kafka did not return offsets for a topic partition");
            }
            // With no committed offset, the Worker uses auto.offset.reset=earliest, so all retained
            // records are pending. Kafka commits the next offset to consume, not the last consumed one.
            var committedOffset = committed.get(partition);
            long nextOffset = committedOffset == null ? earliestInfo.offset() : committedOffset.offset();
            totalLag += Math.max(0L, latestInfo.offset() - nextOffset);
        }
        return totalLag;
    }

    @PreDestroy
    void close() {
        adminClient.close(Duration.ofSeconds(2));
    }
}
