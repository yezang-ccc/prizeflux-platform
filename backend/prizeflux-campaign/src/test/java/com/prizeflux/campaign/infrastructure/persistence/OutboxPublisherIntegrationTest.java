package com.prizeflux.campaign.infrastructure.persistence;

import com.prizeflux.campaign.PrizeFluxCampaignApplication;
import com.prizeflux.campaign.application.DomainEventPublisher;
import com.prizeflux.campaign.application.OutboxEvent;
import com.prizeflux.campaign.application.OutboxPublisher;
import com.prizeflux.campaign.infrastructure.messaging.KafkaEventPublisher;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real MySQL verifies lease ownership, recovery, and Relay state changes. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EmbeddedKafka(partitions = 1, topics = "prizeflux.fulfillment.requested",
        bootstrapServersProperty = "prizeflux.kafka.bootstrap-servers")
@SpringBootTest(classes = PrizeFluxCampaignApplication.class, properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "prizeflux.persistence.campaign.enabled=true",
        "prizeflux.kafka.enabled=true",
        "PFX_JWT_SECRET=test-only-jwt-secret-0123456789abcdef0123456789"
})
class OutboxPublisherIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
    private static final String EVENT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String FIRST_LEASE = "10000000-0000-0000-0000-000000000001";
    private static final String SECOND_LEASE = "20000000-0000-0000-0000-000000000001";

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

    @Autowired JdbcOutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaEventPublisher kafkaPublisher;
    @Autowired EmbeddedKafkaBroker broker;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, aggregate_type, aggregate_id, event_type,
                     schema_version, payload, status, available_at)
                VALUES (?, 'DRAW', 1, 'PrizeFulfillmentRequested', 1, '{}', 'PENDING', ?)
                """, EVENT_ID, Timestamp.from(NOW.minusSeconds(1)));
    }

    @Test
    void expiredLeaseCanBeReclaimedButStaleOwnerCannotMarkSent() {
        List<OutboxEvent> first = outbox.claim(1, FIRST_LEASE, NOW.plusSeconds(10), NOW);
        assertEquals(1, first.size());
        assertEquals(1, first.get(0).attempts());
        assertEquals(1, outbox.releaseExpired(NOW.plusSeconds(11)));

        List<OutboxEvent> second = outbox.claim(1, SECOND_LEASE,
                NOW.plusSeconds(30), NOW.plusSeconds(11));
        assertEquals(1, second.size());
        assertEquals(2, second.get(0).attempts());
        outbox.markSent(EVENT_ID, FIRST_LEASE);
        assertEquals("LEASED", status());
        outbox.markSent(EVENT_ID, SECOND_LEASE);
        assertEquals("SENT", status());
    }

    @Test
    void relayRetriesThenMarksDeadAtAttemptLimit() {
        DomainEventPublisher failingTransport = event -> {
            throw new IllegalStateException("test broker outage");
        };
        OutboxPublisher relay = new OutboxPublisher(outbox, failingTransport,
                Clock.fixed(NOW, ZoneOffset.UTC), 1, Duration.ofSeconds(30), 2);

        assertEquals(1, relay.publishDue());
        assertEquals("PENDING", status());
        assertEquals(1, attempts());
        assertEquals(1, relay.publishDue());
        assertEquals("DEAD", status());
        assertEquals(2, attempts());
    }

    @Test
    void relayMarksSentOnlyAfterRealBrokerAcknowledgement() {
        String topic = "prizeflux.fulfillment.requested";
        var consumerProps = KafkaTestUtils.consumerProps("outbox-relay-test", "false", broker);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, topic);
            OutboxPublisher relay = new OutboxPublisher(outbox, kafkaPublisher,
                    Clock.fixed(NOW, ZoneOffset.UTC), 1, Duration.ofSeconds(30), 2);

            assertEquals(1, relay.publishDue());
            ConsumerRecord<String, String> received = KafkaTestUtils.getSingleRecord(consumer, topic);
            assertEquals(EVENT_ID, received.key());
            assertEquals("{}", received.value());
            assertEquals("SENT", status());
        }
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?",
                String.class, EVENT_ID);
    }

    private int attempts() {
        return jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE event_id = ?",
                Integer.class, EVENT_ID);
    }
}
