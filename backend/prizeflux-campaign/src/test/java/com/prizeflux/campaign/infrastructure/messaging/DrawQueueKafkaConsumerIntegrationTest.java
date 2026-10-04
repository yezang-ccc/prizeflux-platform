package com.prizeflux.campaign.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.PrizeFluxCampaignApplication;
import com.prizeflux.campaign.application.DrawQueueRepository;
import com.prizeflux.campaign.application.DrawQueueRepository.Status;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real MySQL and Kafka verify separate executor consumption and poison-notification DLT handling. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EmbeddedKafka(partitions = 3, topics = {"prizeflux.draw.queued", "prizeflux.draw.queued.dlt",
        "prizeflux.fulfillment.requested"}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@SpringBootTest(classes = PrizeFluxCampaignApplication.class, properties = {
        "spring.autoconfigure.exclude=",
        "spring.main.web-application-type=none",
        "spring.flyway.enabled=true",
        "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "prizeflux.persistence.campaign.enabled=true",
        "prizeflux.kafka.enabled=true",
        "prizeflux.kafka.draw-queue-enabled=true",
        "prizeflux.kafka.draw-queue-consumer-enabled=true",
        "prizeflux.kafka.draw-queue-partitions=3",
        "prizeflux.kafka.bootstrap-servers=${spring.kafka.bootstrap-servers}",
        "prizeflux.kafka.topic-draw-queue=prizeflux.draw.queued",
        "prizeflux.kafka.topic-draw-queue-dlt=prizeflux.draw.queued.dlt",
        "prizeflux.kafka.group-draw-queue=draw-queue-consumer-integration-test",
        "prizeflux.kafka.draw-queue-scan-initial-delay-ms=60000",
        "prizeflux.kafka.draw-queue-scan-delay-ms=60000",
        "prizeflux.traffic.draw-queue.enabled=false",
        "prizeflux.outbox.relay.enabled=false",
        "PFX_JWT_SECRET=test-only-jwt-secret-0123456789abcdef0123456789"
})
@Import(DrawQueueKafkaConsumerIntegrationTest.FixedClockConfiguration.class)
class DrawQueueKafkaConsumerIntegrationTest {
    private static final String QUEUE_TOPIC = "prizeflux.draw.queued";
    private static final String DLT_TOPIC = "prizeflux.draw.queued.dlt";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("prizeflux")
            .withUsername("prizeflux")
            .withPassword("integration-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET time_zone = '+00:00'");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DrawQueueRepository queue;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired EmbeddedKafkaBroker broker;

    @Test
    void kafkaWakeUpExecutesMySqlQueueRecord() throws Exception {
        long campaignId = 97101;
        seed(campaignId);
        long queueId = queue.enqueue(7, campaignId, "kafka-consume-97101", new byte[32]);

        kafka.send(QUEUE_TOPIC, Long.toString(queueId), json.writeValueAsString(
                Map.of("queueRequestId", queueId))).get();

        DrawQueueRepository.Entry completed = awaitQueue(queueId, Status.COMPLETED);
        assertThat(completed.status()).withFailMessage("Queue ended with errorCode=%s", completed.errorCode())
                .isEqualTo(Status.COMPLETED);
        assertThat(completed.result()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT remaining_stock FROM prize_inventory WHERE campaign_id = ?",
                Integer.class, campaignId)).isZero();
        assertThat(jdbc.queryForObject("SELECT used_count FROM user_campaign_quota WHERE campaign_id = ?",
                Integer.class, campaignId)).isOne();
    }

    @Test
    void malformedNotificationIsRetriedThenPublishedToDlt() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        var properties = KafkaTestUtils.consumerProps("draw-queue-dlt-test-" + key, "false", broker);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                properties, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, DLT_TOPIC);
            kafka.send(QUEUE_TOPIC, key, "{invalid-json").get();
            ConsumerRecord<String, String> deadLetter = KafkaTestUtils.getSingleRecord(consumer, DLT_TOPIC,
                    Duration.ofSeconds(20));
            assertThat(deadLetter.key()).isEqualTo(key);
            assertThat(deadLetter.value()).isEqualTo("{invalid-json");
            assertThat(deadLetter.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
        }
    }

    private void seed(long campaignId) {
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, role, state)
                VALUES (7, 'queue-test-user', ?, 'USER', 'ACTIVE')
                ON DUPLICATE KEY UPDATE role = 'USER', state = 'ACTIVE'
                """, "$2b$12$" + "a".repeat(53));
        jdbc.update("""
                INSERT INTO campaign (id, name, starts_at, ends_at, status)
                VALUES (?, 'Kafka queue test', '2026-09-23 00:00:00',
                        '2026-09-24 00:00:00', 'PUBLISHED')
                """, campaignId);
        jdbc.update("""
                INSERT INTO campaign_config_version (campaign_id, config_version, daily_limit, state, published_at)
                VALUES (?, 1, 5, 'PUBLISHED', UTC_TIMESTAMP(6))
                """, campaignId);
        jdbc.update("""
                INSERT INTO prize_config (campaign_id, config_version, prize_id, name, probability, total_stock)
                VALUES (?, 1, 11, 'Kafka test prize', 1.000000, 1)
                """, campaignId);
        jdbc.update("""
                INSERT INTO prize_inventory (campaign_id, config_version, prize_id, remaining_stock)
                VALUES (?, 1, 11, 1)
                """, campaignId);
    }

    private DrawQueueRepository.Entry awaitQueue(long id, Status expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            var entry = queue.findById(id).orElseThrow();
            if (entry.status() == expected) return entry;
            Thread.sleep(50);
        }
        return queue.findById(id).orElseThrow();
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedQueueTestClock() {
            return Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}
