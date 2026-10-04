package com.prizeflux.worker.application;

import com.prizeflux.contract.event.PrizeFulfillmentEvent;
import com.prizeflux.worker.PrizeFluxWorkerApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Real MySQL verifies the two uniqueness guards and the ledger/marker transaction. */
@Testcontainers
@SpringBootTest(classes = PrizeFluxWorkerApplication.class,
        properties = {"spring.kafka.listener.auto-startup=false", "prizeflux.worker.retry.enabled=true",
                "prizeflux.worker.retry.scheduler-enabled=false"})
class FulfillmentIdempotencyIntegrationTest {

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

    @Autowired FulfillmentApplicationService service;
    @Autowired FulfillmentRetryService retryService;
    @Autowired FulfillmentRepository repository;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FulfillmentExecutor executor;

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM fulfillment_compensation_task");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM fulfillment");
        reset(executor);
    }

    @Test
    void sameEventAndSameDrawAreIdempotent() {
        PrizeFulfillmentEvent first = event("event-1", 101);
        assertEquals(ConsumeOutcome.PROCESSED, service.consume(first, "{}"));
        assertEquals(ConsumeOutcome.DUPLICATE, service.consume(first, "{}"));
        assertEquals(ConsumeOutcome.DUPLICATE,
                service.consume(event("event-2", 101), "{}"));

        assertEquals(1, count("fulfillment"));
        assertEquals(1, count("processed_event"));
    }

    @Test
    void markerFailureRollsBackNewLedger() {
        jdbc.update("INSERT INTO processed_event (event_id, consumed_at) VALUES (?, UTC_TIMESTAMP(6))",
                "event-3");

        assertThrows(DuplicateKeyException.class,
                () -> service.consume(event("event-3", 103), "{}"));

        assertEquals(0, count("fulfillment"));
        assertEquals(1, count("processed_event"));
    }

    @Test
    void transientFailureIsRetriedAndEventuallyCompletesOnce() {
        service.consume(event("event-retry", 201), "{}");
        doThrow(new FulfillmentExecutionException("TEMPORARY_DOWNSTREAM", false))
                .doNothing().when(executor).execute(anyString(), anyString());

        retryService.processDue();
        assertEquals("RETRYING", status(201));
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempt_count FROM fulfillment WHERE draw_id = 201", Integer.class));

        jdbc.update("UPDATE fulfillment SET next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE draw_id = 201");
        retryService.processDue();
        assertEquals("SUCCEEDED", status(201));
        assertEquals(2, jdbc.queryForObject(
                "SELECT attempt_count FROM fulfillment WHERE draw_id = 201", Integer.class));
        assertEquals(1, count("fulfillment"));
        verify(executor, times(2)).execute(anyString(), anyString());
    }

    @Test
    void permanentFailureCreatesOneDurableReviewTaskAndStopsRetrying() {
        service.consume(event("event-permanent", 202), "{}");
        doThrow(new FulfillmentExecutionException("INVALID_DESTINATION", true))
                .when(executor).execute(anyString(), anyString());

        retryService.processDue();
        retryService.processDue();

        assertEquals("MANUAL_REVIEW", status(202));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_compensation_task WHERE draw_id = 202 AND state = 'OPEN'", Integer.class));
        verify(executor, times(1)).execute(anyString(), anyString());
    }

    @Test
    void transientRetryExhaustionCreatesOneReviewTask() {
        var event = event("event-exhausted", 204);
        service.consume(event, "{}");
        doThrow(new FulfillmentExecutionException("TEMPORARY_DOWNSTREAM", false))
                .when(executor).execute(anyString(), anyString());

        retryService.processDue();
        jdbc.update("UPDATE fulfillment SET next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE draw_id = 204");
        retryService.processDue();
        jdbc.update("UPDATE fulfillment SET next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE draw_id = 204");
        retryService.processDue();

        assertEquals("MANUAL_REVIEW", status(204));
        assertEquals(3, jdbc.queryForObject(
                "SELECT attempt_count FROM fulfillment WHERE draw_id = 204", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_compensation_task WHERE draw_id = 204", Integer.class));
        assertEquals(ConsumeOutcome.DUPLICATE, service.consume(event, "{}"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_compensation_task WHERE draw_id = 204", Integer.class));
    }

    @Test
    void expiredLeaseCanBeReclaimedAndStaleWorkerIsFenced() {
        service.consume(event("event-lease", 203), "{}");
        Instant now = Instant.now();
        var first = repository.claimDue(now, now.plusSeconds(60), "lease-old", 10).get(0);
        var reclaimed = repository.claimDue(now.plusSeconds(61), now.plusSeconds(121), "lease-new", 10).get(0);

        assertFalse(repository.markSucceeded(first.id(), first.leaseToken(), now.plusSeconds(62)));
        assertTrue(repository.markSucceeded(reclaimed.id(), reclaimed.leaseToken(), now.plusSeconds(63)));
        assertEquals("SUCCEEDED", status(203));
        assertEquals(2, jdbc.queryForObject(
                "SELECT attempt_count FROM fulfillment WHERE draw_id = 203", Integer.class));
    }

    private PrizeFulfillmentEvent event(String eventId, long drawId) {
        return new PrizeFulfillmentEvent(1, eventId, drawId, 10, 1, 20, 30,
                Instant.parse("2026-09-24T00:00:00Z"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String status(long drawId) {
        return jdbc.queryForObject("SELECT status FROM fulfillment WHERE draw_id = ?", String.class, drawId);
    }
}
