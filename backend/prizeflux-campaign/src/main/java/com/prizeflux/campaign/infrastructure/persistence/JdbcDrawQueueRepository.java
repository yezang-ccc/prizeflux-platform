package com.prizeflux.campaign.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.application.DrawOutcome;
import com.prizeflux.campaign.application.DrawQueueNotification;
import com.prizeflux.campaign.application.DrawQueueRepository;
import com.prizeflux.campaign.application.DrawResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** MySQL 任务记录与 Outbox 通知同连接、同事务提交。 */
@Repository
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcDrawQueueRepository implements DrawQueueRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcDrawQueueRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Entry> findByUserAndKey(long userId, String requestKey) {
        return query("WHERE q.user_id = ? AND q.request_key = ?", userId, requestKey);
    }

    @Override
    public Optional<Entry> findById(long id) {
        return query("WHERE q.id = ?", id);
    }

    @Override
    public PressureSnapshot pressureSnapshot() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS pending_count,
                       COALESCE(GREATEST(
                           TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(6)) DIV 1000,
                           0), 0) AS oldest_age_ms
                FROM draw_queue_request
                WHERE status IN ('QUEUED', 'PROCESSING', 'RETRY_WAIT')
                """, (rs, row) -> new PressureSnapshot(
                rs.getLong("pending_count"), rs.getLong("oldest_age_ms")));
    }

    private Optional<Entry> query(String where, Object... parameters) {
        List<Entry> rows = jdbc.query("""
                SELECT q.id, q.user_id, q.campaign_id, q.request_key, q.request_fingerprint,
                       q.status, q.error_code, q.last_error_code, q.attempts, q.claim_token,
                       q.lease_expires_at, q.next_attempt_at, q.created_at, q.completed_at,
                       d.id AS result_id, d.outcome, d.prize_id, d.fulfillment_status
                FROM draw_queue_request q
                LEFT JOIN draw_result d ON d.id = q.draw_result_id
                """ + where, (rs, row) -> {
            long resultId = rs.getLong("result_id");
            DrawResult result = rs.wasNull() ? null : new DrawResult(resultId,
                    rs.getLong("campaign_id"), rs.getLong("user_id"),
                    DrawOutcome.valueOf(rs.getString("outcome")),
                    rs.getObject("prize_id", Long.class), rs.getString("fulfillment_status"));
            var created = rs.getTimestamp("created_at").toInstant();
            var completedTimestamp = rs.getTimestamp("completed_at");
            var leaseTimestamp = rs.getTimestamp("lease_expires_at");
            var nextAttempt = rs.getTimestamp("next_attempt_at").toInstant();
            return new Entry(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("campaign_id"),
                    rs.getString("request_key"), rs.getBytes("request_fingerprint"),
                    Status.valueOf(rs.getString("status")), result, rs.getString("error_code"),
                    rs.getInt("attempts"), rs.getString("last_error_code"), rs.getString("claim_token"),
                    leaseTimestamp == null ? null : leaseTimestamp.toInstant(), nextAttempt,
                    created, completedTimestamp == null ? null : completedTimestamp.toInstant());
        }, parameters);
        return rows.stream().findFirst();
    }

    @Override
    public List<Long> findDueIds(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid queue scan limit");
        return jdbc.queryForList("""
                SELECT id FROM draw_queue_request
                WHERE ((status IN ('QUEUED', 'RETRY_WAIT') AND next_attempt_at <= UTC_TIMESTAMP(6))
                    OR (status = 'PROCESSING' AND lease_expires_at <= UTC_TIMESTAMP(6)))
                ORDER BY CASE WHEN status = 'PROCESSING' THEN lease_expires_at ELSE next_attempt_at END,
                         created_at, id
                LIMIT ?
                """, Long.class, limit);
    }

    @Override
    public long enqueue(long userId, long campaignId, String requestKey, byte[] fingerprint) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO draw_queue_request (user_id, campaign_id, request_key,
                                                   request_fingerprint, status)
                    VALUES (?, ?, ?, ?, 'QUEUED')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setLong(2, campaignId);
            statement.setString(3, requestKey);
            statement.setBytes(4, fingerprint);
            return statement;
        }, key);
        Number generated = key.getKey();
        if (generated == null || generated.longValue() <= 0) {
            throw new IllegalStateException("Database did not generate a positive queue ID");
        }
        long id = generated.longValue();
        String payload;
        try {
            payload = json.writeValueAsString(new DrawQueueNotification(id));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize draw queue notification", exception);
        }
        jdbc.update("""
                INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id, event_type,
                                          schema_version, payload, status, available_at)
                VALUES (?, 'CAMPAIGN', ?, 'DrawQueued', 1, ?, 'PENDING', UTC_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), campaignId, payload);
        return id;
    }

    @Override
    public boolean claim(long id, String claimToken, java.time.Duration leaseDuration) {
        return jdbc.update("""
                UPDATE draw_queue_request
                SET status = 'PROCESSING', claim_token = ?,
                    lease_expires_at = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)), error_code = NULL
                WHERE id = ? AND (
                    (status IN ('QUEUED', 'RETRY_WAIT') AND next_attempt_at <= UTC_TIMESTAMP(6))
                    OR (status = 'PROCESSING' AND lease_expires_at <= UTC_TIMESTAMP(6)))
                """, claimToken, durationMicros(leaseDuration), id) == 1;
    }

    @Override
    public boolean recordAttempt(long id, String claimToken, int maxAttempts) {
        return jdbc.update("""
                UPDATE draw_queue_request SET attempts = attempts + 1
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ? AND attempts < ?
                """, id, claimToken, maxAttempts) == 1;
    }

    @Override
    public boolean heartbeat(long id, String claimToken, java.time.Duration leaseDuration) {
        return jdbc.update("""
                UPDATE draw_queue_request
                SET lease_expires_at = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6))
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, durationMicros(leaseDuration), id, claimToken) == 1;
    }

    @Override
    public boolean complete(long id, String claimToken, long drawResultId) {
        int changed = jdbc.update("""
                UPDATE draw_queue_request
                SET status = 'COMPLETED', draw_result_id = ?, completed_at = UTC_TIMESTAMP(6),
                    error_code = NULL, claim_token = NULL, lease_expires_at = NULL
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, drawResultId, id, claimToken);
        if (changed != 0) return true;
        Entry current = findById(id).orElseThrow(() -> new IllegalStateException("Queued draw record not found"));
        if (current.status() != Status.COMPLETED || current.result() == null
                || current.result().id() != drawResultId) {
            return false;
        }
        return true;
    }

    @Override
    public boolean reject(long id, String claimToken, String errorCode) {
        return jdbc.update("""
                UPDATE draw_queue_request
                SET status = 'REJECTED', error_code = ?, last_error_code = ?,
                    completed_at = UTC_TIMESTAMP(6), claim_token = NULL, lease_expires_at = NULL
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, errorCode, errorCode, id, claimToken) == 1;
    }

    @Override
    public boolean retry(long id, String claimToken, String errorCode, java.time.Duration delay) {
        return jdbc.update("""
                UPDATE draw_queue_request
                SET status = 'RETRY_WAIT', last_error_code = ?,
                    next_attempt_at = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)),
                    claim_token = NULL, lease_expires_at = NULL
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, errorCode, durationMicros(delay), id, claimToken) == 1;
    }

    @Override
    public boolean manualReview(long id, String claimToken, String errorCode) {
        return jdbc.update("""
                UPDATE draw_queue_request
                SET status = 'MANUAL_REVIEW', error_code = ?, last_error_code = ?,
                    claim_token = NULL, lease_expires_at = NULL
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, errorCode, errorCode, id, claimToken) == 1;
    }

    private static long durationMicros(java.time.Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("A positive queue duration is required");
        }
        try {
            return Math.max(1L, duration.toNanos() / 1000L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Queue duration is too large", overflow);
        }
    }
}
