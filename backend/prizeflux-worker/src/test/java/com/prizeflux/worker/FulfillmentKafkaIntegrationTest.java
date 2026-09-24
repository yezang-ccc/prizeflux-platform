package com.prizeflux.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.contract.event.PrizeFulfillmentEvent;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A real broker delivery reaches the Worker listener and its MySQL idempotency transaction. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EmbeddedKafka(partitions = 1, topics = "prizeflux.fulfillment.requested",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@SpringBootTest(classes = PrizeFluxWorkerApplication.class,
        properties = "prizeflux.worker.retry.enabled=false")
class FulfillmentKafkaIntegrationTest {
    private static final String TOPIC = "prizeflux.fulfillment.requested";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("prizeflux_worker")
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

    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void duplicateBrokerDeliveriesCreateOneLedgerAndOneMarker() throws Exception {
        PrizeFulfillmentEvent event = new PrizeFulfillmentEvent(1,
                "00000000-0000-0000-0000-000000000101", 101, 10, 1, 20, 30,
                Instant.parse("2026-09-24T00:00:00Z"));
        String payload = mapper.writeValueAsString(event);
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
            producer.send(new ProducerRecord<>(TOPIC, event.eventId(), payload))
                    .get(10, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(TOPIC, event.eventId(), payload))
                    .get(10, TimeUnit.SECONDS);
        }

        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            Instant deadline = Instant.now().plusSeconds(20);
            long committedOffset = -1;
            while (Instant.now().isBefore(deadline)) {
                var offsets = admin.listConsumerGroupOffsets("prizeflux-worker")
                        .partitionsToOffsetAndMetadata().get(3, TimeUnit.SECONDS);
                committedOffset = offsets.getOrDefault(new TopicPartition(TOPIC, 0), null) == null
                        ? -1 : offsets.get(new TopicPartition(TOPIC, 0)).offset();
                if (committedOffset >= 2) {
                    break;
                }
                Thread.sleep(100);
            }
            assertTrue(committedOffset >= 2, "Worker did not acknowledge both broker records");
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM fulfillment", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class));
    }
}
