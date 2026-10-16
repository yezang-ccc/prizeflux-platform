package com.prizeflux.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.contract.event.PrizeFulfillmentEvent;
import com.prizeflux.worker.application.FulfillmentRepository;
import com.prizeflux.worker.infrastructure.observability.WorkerKafkaLagMetrics;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A real broker delivery reaches the Worker listener and its MySQL idempotency transaction. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EmbeddedKafka(partitions = 1, topics = "prizeflux.fulfillment.requested",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ActiveProfiles("local-full")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PrizeFluxWorkerApplication.class,
        properties = {
                "prizeflux.worker.retry.enabled=false",
                "prizeflux.worker.retry.scheduler-enabled=false",
                "spring.main.web-application-type=servlet",
                "server.port=-1",
                "management.server.port=0",
                "management.server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=prometheus",
                "management.prometheus.metrics.export.enabled=true",
                "pfx.observability.metrics.scrape-auth-enabled=true",
                "pfx.observability.metrics.scrape-username=test-scraper",
                "pfx.observability.metrics.scrape-password=test-only-scrape-secret"
        })
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
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkerKafkaLagMetrics lagMetrics;
    @Autowired KafkaListenerEndpointRegistry listenerRegistry;
    @MockitoSpyBean(name = "jdbcFulfillmentRepository") FulfillmentRepository fulfillments;
    @LocalManagementPort int managementPort;

    @Test
    void persistenceFailuresRecoverAndDuplicateBrokerDeliveriesCreateOneLedger() throws Exception {
        AtomicBoolean persistenceUnavailable = new AtomicBoolean(true);
        AtomicInteger failedAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (persistenceUnavailable.get()) {
                failedAttempts.incrementAndGet();
                throw new IllegalStateException("injected persistence outage");
            }
            return invocation.callRealMethod();
        }).when(fulfillments).create(any(), any(), any(), any());

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
            Instant failureDeadline = Instant.now().plusSeconds(45);
            while (failedAttempts.get() < 12 && Instant.now().isBefore(failureDeadline)) {
                Thread.sleep(100);
            }
            assertTrue(failedAttempts.get() >= 12, "Worker did not retry beyond the default limit");
            assertTrue(groupOffset(admin) < 1, "Failed event offset must remain uncommitted");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM fulfillment", Integer.class));
            persistenceUnavailable.set(false);

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

        // Deterministically replay already-processed broker records as if the
        // consumer group had lost its committed offset. This validates at-least-once
        // idempotency; it does not simulate a hard process kill in the commit window.
        MessageListenerContainer listener = listenerRegistry.getListenerContainers().iterator().next();
        listener.stop();
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            admin.alterConsumerGroupOffsets("prizeflux-worker",
                    Map.of(partition, new OffsetAndMetadata(0))).all().get(5, TimeUnit.SECONDS);
            assertEquals(0, groupOffset(admin));
        }
        listener.start();
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            awaitGroupOffset(admin, 2);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM fulfillment", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class));

        lagMetrics.sampleNow();

        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort
                + "/actuator/prometheus")).GET().build();
        var client = HttpClient.newHttpClient();
        var unauthorized = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthorized.statusCode());
        var authorizedRequest = HttpRequest.newBuilder(request.uri())
                .header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
                        "test-scraper:test-only-scrape-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .GET().build();
        var response = client.send(authorizedRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("prizeflux_worker_events_total{outcome=\"processed\"} 1.0"));
        assertTrue(response.body().contains("prizeflux_worker_events_total{outcome=\"duplicate\"} 3.0"));
        assertTrue(response.body().contains("prizeflux_worker_kafka_consumer_lag 0.0"));
    }

    private long groupOffset(AdminClient admin) throws Exception {
        var offsets = admin.listConsumerGroupOffsets("prizeflux-worker")
                .partitionsToOffsetAndMetadata().get(3, TimeUnit.SECONDS);
        OffsetAndMetadata offset = offsets.get(new TopicPartition(TOPIC, 0));
        return offset == null ? -1 : offset.offset();
    }

    private void awaitGroupOffset(AdminClient admin, long target) throws Exception {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            if (groupOffset(admin) >= target) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Worker did not commit replayed broker records");
    }

}
