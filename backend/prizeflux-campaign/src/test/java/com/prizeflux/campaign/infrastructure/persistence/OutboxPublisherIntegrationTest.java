package com.prizeflux.campaign.infrastructure.persistence;

import com.prizeflux.campaign.PrizeFluxCampaignApplication;
import com.prizeflux.campaign.application.DomainEventPublisher;
import com.prizeflux.campaign.application.OutboxEvent;
import com.prizeflux.campaign.application.OutboxPublisher;
import com.prizeflux.campaign.application.OutboxTelemetry;
import com.prizeflux.campaign.infrastructure.messaging.KafkaEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real MySQL verifies lease ownership, recovery, and Relay state changes. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EmbeddedKafka(partitions = 1, topics = {"prizeflux.fulfillment.requested", "prizeflux.draw.queued",
        "prizeflux.draw.queued.dlt"},
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
    @Autowired OutboxTelemetry telemetry;
    @Autowired MeterRegistry meterRegistry;
    @Autowired EmbeddedKafkaBroker broker;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, aggregate_type, aggregate_id, event_type,
                     schema_version, payload, status, available_at, created_at)
                VALUES (?, 'DRAW', 1, 'PrizeFulfillmentRequested', 1, '{}', 'PENDING', ?, ?)
                """, EVENT_ID, Timestamp.from(NOW.minusSeconds(1)),
                LocalDateTime.ofInstant(NOW.minusSeconds(5), ZoneOffset.UTC));
    }

    @Test
    void expiredLeaseCanBeReclaimedButStaleOwnerCannotMarkSent() {
        List<OutboxEvent> first = outbox.claim(1, FIRST_LEASE, NOW.plusSeconds(10), NOW);
        assertEquals(1, first.size());
        assertEquals(1, first.get(0).attempts());
        assertEquals(NOW.minusSeconds(5), first.get(0).createdAt());
        assertEquals(1, outbox.releaseExpired(NOW.plusSeconds(11)));

        List<OutboxEvent> second = outbox.claim(1, SECOND_LEASE,
                NOW.plusSeconds(30), NOW.plusSeconds(11));
        assertEquals(1, second.size());
        assertEquals(2, second.get(0).attempts());
        assertEquals(false, outbox.markSent(EVENT_ID, FIRST_LEASE));
        assertEquals("LEASED", status());
        assertEquals(true, outbox.markSent(EVENT_ID, SECOND_LEASE));
        assertEquals("SENT", status());
    }

    @Test
    void outboxPressureCountsBacklogAndDeadLettersButExcludesTerminalAge() {
        jdbc.update("UPDATE outbox_event SET created_at = UTC_TIMESTAMP(6) - INTERVAL 10 SECOND WHERE event_id = ?",
                EVENT_ID);
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                     status, available_at, created_at)
                VALUES ('00000000-0000-0000-0000-000000000002', 'DRAW', 2, 'Test', 1, '{}',
                        'PENDING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) - INTERVAL 30 SECOND)
                """);
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                     status, available_at, lease_token, lease_until, created_at)
                VALUES ('00000000-0000-0000-0000-000000000003', 'DRAW', 3, 'Test', 1, '{}',
                        'LEASED', UTC_TIMESTAMP(6), '30000000-0000-0000-0000-000000000003',
                        UTC_TIMESTAMP(6) + INTERVAL 1 MINUTE, UTC_TIMESTAMP(6) - INTERVAL 20 SECOND)
                """);
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                     status, available_at, created_at)
                VALUES ('00000000-0000-0000-0000-000000000004', 'DRAW', 4, 'Test', 1, '{}',
                        'DEAD', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE)
                """);
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                     status, available_at, created_at, sent_at)
                VALUES ('00000000-0000-0000-0000-000000000005', 'DRAW', 5, 'Test', 1, '{}',
                        'SENT', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) - INTERVAL 3 MINUTE, UTC_TIMESTAMP(6))
                """);

        var snapshot = outbox.pressureSnapshot();

        assertEquals(2, snapshot.pendingCount());
        assertEquals(1, snapshot.leasedCount());
        assertEquals(1, snapshot.deadCount());
        org.junit.jupiter.api.Assertions.assertTrue(snapshot.oldestAgeMillis() >= 30_000);
        org.junit.jupiter.api.Assertions.assertTrue(snapshot.oldestAgeMillis() < 60_000,
                "terminal DEAD/SENT rows must not drive the active backlog age");
    }

    @Test
    void relaySchedulesRetriesWithBackoffThenMarksDeadAtAttemptLimit() {
        DomainEventPublisher failingTransport = event -> {
            throw new IllegalStateException("test broker outage");
        };
        OutboxPublisher relay = new OutboxPublisher(outbox, failingTransport,
                telemetry, Clock.fixed(NOW, ZoneOffset.UTC), 1, Duration.ofSeconds(30), 2,
                Duration.ofSeconds(2), Duration.ofSeconds(30),
                new com.prizeflux.campaign.application.BoundedOutboxDispatcher(1));

        assertEquals(1, relay.publishDue());
        assertEquals("PENDING", status());
        assertEquals(1, attempts());
        assertEquals(NOW.plusSeconds(2), availableAt());
        OutboxPublisher secondAttempt = new OutboxPublisher(outbox, failingTransport,
                telemetry, Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC), 1, Duration.ofSeconds(30), 2,
                Duration.ofSeconds(2), Duration.ofSeconds(30),
                new com.prizeflux.campaign.application.BoundedOutboxDispatcher(1));
        assertEquals(1, secondAttempt.publishDue());
        assertEquals("DEAD", status());
        assertEquals(2, attempts());
    }

    @Test
    void parallelRelayKeepsPerEventFencingAndRealBrokerConfirmation() {
        insertParallelPeer();
        var dispatcher = new com.prizeflux.campaign.application.BoundedOutboxDispatcher(2);
        DomainEventPublisher transport = event -> {
            kafkaPublisher.publish(event);
            if (EVENT_ID.equals(event.eventId())) {
                // Simulate a new lease owner before this broker-confirmed callback returns.
                jdbc.update("UPDATE outbox_event SET lease_token = 'new-owner' WHERE event_id = ?", EVENT_ID);
            }
        };
        try {
            var relay = new OutboxPublisher(outbox, transport, telemetry,
                    Clock.fixed(NOW, ZoneOffset.UTC), 50, Duration.ofSeconds(30), 3,
                    Duration.ofSeconds(2), Duration.ofSeconds(30), dispatcher);
            assertEquals(2, relay.publishDue());
            assertEquals("LEASED", status());
            assertEquals("new-owner", jdbc.queryForObject(
                    "SELECT lease_token FROM outbox_event WHERE event_id = ?", String.class, EVENT_ID));
            assertEquals("SENT", jdbc.queryForObject(
                    "SELECT status FROM outbox_event WHERE event_id = 'parallel-event-2'", String.class));
        } finally { dispatcher.close(); }
    }

    @Test
    void parallelTransportFailureRetriesOnlyFailedEvent() {
        insertParallelPeer();
        var dispatcher = new com.prizeflux.campaign.application.BoundedOutboxDispatcher(2);
        DomainEventPublisher transport = event -> {
            if (EVENT_ID.equals(event.eventId())) { throw new IllegalStateException("test broker failure"); }
            kafkaPublisher.publish(event);
        };
        try {
            var relay = new OutboxPublisher(outbox, transport, telemetry,
                    Clock.fixed(NOW, ZoneOffset.UTC), 50, Duration.ofSeconds(30), 3,
                    Duration.ofSeconds(2), Duration.ofSeconds(30), dispatcher);
            assertEquals(2, relay.publishDue());
            assertEquals("PENDING", status());
            assertEquals(1, attempts());
            assertEquals("SENT", jdbc.queryForObject(
                    "SELECT status FROM outbox_event WHERE event_id = 'parallel-event-2'", String.class));
        } finally { dispatcher.close(); }
    }

    private void insertParallelPeer() {
        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, aggregate_type, aggregate_id, event_type,
                     schema_version, payload, status, available_at, created_at)
                SELECT 'parallel-event-2', aggregate_type, 2, event_type,
                     schema_version, payload, status, available_at, created_at
                FROM outbox_event WHERE event_id = ?
                """, EVENT_ID);
    }

    @Test
    void relayMarksSentOnlyAfterRealBrokerAcknowledgement() {
        String topic = "prizeflux.fulfillment.requested";
        var confirmationTimer = meterRegistry.timer("prizeflux.outbox.create.to.confirm");
        long priorCount = confirmationTimer.count();
        double priorSeconds = confirmationTimer.totalTime(TimeUnit.SECONDS);
        var consumerProps = KafkaTestUtils.consumerProps("outbox-relay-test", "false", broker);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            // The suite also publishes parallel events to this topic; observe this invocation only.
            broker.consumeFromAnEmbeddedTopic(consumer, true, topic);
            OutboxPublisher relay = new OutboxPublisher(outbox, kafkaPublisher,
                    telemetry, Clock.fixed(NOW, ZoneOffset.UTC), 1, Duration.ofSeconds(30), 2,
                    Duration.ofSeconds(2), Duration.ofSeconds(30),
                    new com.prizeflux.campaign.application.BoundedOutboxDispatcher(1));

            assertEquals(1, relay.publishDue());
            ConsumerRecord<String, String> received = KafkaTestUtils.getSingleRecord(consumer, topic);
            assertEquals(EVENT_ID, received.key());
            assertEquals("{}", received.value());
            assertEquals("SENT", status());
            assertEquals(priorCount + 1, confirmationTimer.count());
            assertEquals(5.0, confirmationTimer.totalTime(TimeUnit.SECONDS) - priorSeconds);
        }
    }

    @Test
void queuedDrawEventsUseStableQueueRequestPartitionKey() throws Exception {
        String topic = "prizeflux.draw.queued";
        var consumerProps = KafkaTestUtils.consumerProps("draw-queue-routing-test", "false", broker);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, topic);
            kafkaPublisher.publish(new OutboxEvent("30000000-0000-0000-0000-000000000001",
                    "DrawQueued", 987654, 1, "{\"queueRequestId\":42}", 0));
            ConsumerRecord<String, String> received = KafkaTestUtils.getSingleRecord(consumer, topic);
            assertEquals("42", received.key());
            assertEquals("{\"queueRequestId\":42}", received.value());
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

    private Instant availableAt() {
        return jdbc.queryForObject("SELECT available_at FROM outbox_event WHERE event_id = ?",
                Timestamp.class, EVENT_ID).toInstant();
    }
}
