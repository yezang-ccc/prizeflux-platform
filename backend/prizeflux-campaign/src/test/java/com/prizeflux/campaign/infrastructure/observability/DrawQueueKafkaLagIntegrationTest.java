package com.prizeflux.campaign.infrastructure.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.application.OutboxEvent;
import com.prizeflux.campaign.infrastructure.messaging.KafkaEventPublisher;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.metrics.KafkaMetricsAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Embedded real broker; no Docker, product database or external network dependency. */
@SpringJUnitConfig(DrawQueueKafkaLagIntegrationTest.TestConfiguration.class)
@EmbeddedKafka(partitions = 1, topics = "lag-draw-queue-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DrawQueueKafkaLagIntegrationTest {
    @Autowired EmbeddedKafkaBroker broker;

    @Test
    void observesUncommittedBacklogThenZeroAfterCommitAndBindsNativeCommitMetrics() throws Exception {
        String topic = "lag-draw-queue-test";
        String group = "draw-lag-" + UUID.randomUUID();
        var reader = new KafkaAdminDrawQueueLagReader(new KafkaAdmin(Map.of(
                "bootstrap.servers", broker.getBrokersAsString())), topic, group);
        var producerFactory = new DefaultKafkaProducerFactory<>(KafkaTestUtils.producerProps(broker),
                new StringSerializer(), new StringSerializer());
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var kafka = new KafkaTemplate<>(producerFactory);
            var publisher = new KafkaEventPublisher(kafka, new ObjectMapper(), "unused-fulfillment",
                    topic, Duration.ofSeconds(10), registry);
            publisher.publish(new OutboxEvent("first", "DrawQueued", 1, 1,
                    "{\"queueRequestId\":1}", 1));
            publisher.publish(new OutboxEvent("second", "DrawQueued", 1, 1,
                    "{\"queueRequestId\":2}", 1));
            assertThat(registry.get("prizeflux.kafka.publish.stage").tags("kind", "draw_queue",
                    "stage", "broker_ack_wait", "outcome", "success").timer().count()).isEqualTo(2);
            assertThat(reader.readLag()).isEqualTo(2);

            var props = KafkaTestUtils.consumerProps(group, "false", broker);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            var factory = new DefaultKafkaConsumerFactory<>(props,
                    new StringDeserializer(), new StringDeserializer());
            // Exercise the same Boot customizer used by Campaign's auto-configured factory.
            new KafkaMetricsAutoConfiguration().kafkaConsumerMetrics(registry).customize(factory);
            try (var consumer = factory.createConsumer()) {
                var partition = new TopicPartition(topic, 0);
                consumer.assign(List.of(partition));
                consumer.seekToBeginning(List.of(partition));
                var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 2);
                assertThat(records.count()).isEqualTo(2);
                // Fetching/processing is not acknowledgement: group lag stays 2 until commit.
                assertThat(reader.readLag()).isEqualTo(2);
                consumer.commitSync(Map.of(partition, new OffsetAndMetadata(2)), Duration.ofSeconds(10));
                assertThat(reader.readLag()).isZero();
                assertThat(registry.get("kafka.consumer.coordinator.commit.latency.avg").gauge().value())
                        .isGreaterThanOrEqualTo(0);
                assertThat(registry.get("kafka.consumer.coordinator.commit.total").functionCounter().count())
                        .isGreaterThanOrEqualTo(1);
                assertThat(registry.scrape()).contains("kafka_consumer_coordinator_commit_latency_avg{",
                        "kafka_consumer_coordinator_commit_total{", "prizeflux_kafka_publish_stage_seconds_count{");
            }
        } finally {
            producerFactory.destroy();
            reader.close();
            registry.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration { }
}
