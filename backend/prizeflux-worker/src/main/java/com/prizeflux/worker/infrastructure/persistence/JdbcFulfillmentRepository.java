package com.prizeflux.worker.infrastructure.persistence;

import com.prizeflux.contract.event.FulfillmentStatus;
import com.prizeflux.contract.event.PrizeFulfillmentEvent;
import com.prizeflux.worker.application.FulfillmentClaim;
import com.prizeflux.worker.application.FulfillmentRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 发奖账本 JDBC 实现：只把唯一键冲突视为重放，不吞掉其他数据错误。
 */
@Repository
public class JdbcFulfillmentRepository implements FulfillmentRepository {
    private final JdbcTemplate jdbc;

    public JdbcFulfillmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean create(PrizeFulfillmentEvent event, FulfillmentStatus status, String payload, Instant now) {
        try {
            jdbc.update("""
                INSERT INTO fulfillment
                    (event_id, draw_id, campaign_id, user_id, prize_id, status, next_attempt_at,
                     payload, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            event.eventId(), event.drawId(), event.campaignId(), event.userId(), event.prizeId(),
                    status.name(), Timestamp.from(now), payload, Timestamp.from(now), Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override
    @Transactional
    public List<FulfillmentClaim> claimDue(Instant now, Instant leaseUntil, String leaseToken, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid claim limit");
        List<FulfillmentClaim> due = jdbc.query("""
                SELECT id, draw_id, event_id, payload, attempt_count
                FROM fulfillment
                WHERE (status IN ('PENDING', 'RETRYING') AND next_attempt_at <= ?)
                   OR (status = 'PROCESSING' AND lease_until <= ?)
                ORDER BY next_attempt_at, id
                LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new FulfillmentClaim(rs.getLong("id"), rs.getLong("draw_id"),
                rs.getString("event_id"), rs.getString("payload"), rs.getInt("attempt_count") + 1,
                leaseToken), Timestamp.from(now), Timestamp.from(now), limit);
        for (FulfillmentClaim claim : due) {
            jdbc.update("""
                    UPDATE fulfillment
                    SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                        lease_token = ?, lease_until = ?, updated_at = ?
                    WHERE id = ?
                    """, leaseToken, Timestamp.from(leaseUntil), Timestamp.from(now), claim.id());
        }
        return due;
    }

    @Override
    public boolean markSucceeded(long id, String leaseToken, Instant now) {
        return jdbc.update("""
                UPDATE fulfillment SET status = 'SUCCEEDED', lease_token = NULL, lease_until = NULL,
                    last_error_code = NULL, updated_at = ?
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, Timestamp.from(now), id, leaseToken) == 1;
    }

    @Override
    public boolean scheduleRetry(long id, String leaseToken, Instant nextAttemptAt,
            String errorCode, Instant now) {
        return jdbc.update("""
                UPDATE fulfillment SET status = 'RETRYING', next_attempt_at = ?, lease_token = NULL,
                    lease_until = NULL, last_error_code = ?, updated_at = ?
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, Timestamp.from(nextAttemptAt), errorCode, Timestamp.from(now), id, leaseToken) == 1;
    }

    @Override
    @Transactional
    public boolean moveToManualReview(long id, long drawId, String leaseToken, String reasonCode, Instant now) {
        int changed = jdbc.update("""
                UPDATE fulfillment SET status = 'MANUAL_REVIEW', lease_token = NULL, lease_until = NULL,
                    last_error_code = ?, updated_at = ?
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, reasonCode, Timestamp.from(now), id, leaseToken);
        if (changed == 1) {
            jdbc.update("""
                    INSERT INTO fulfillment_compensation_task (fulfillment_id, draw_id, reason_code, created_at)
                    VALUES (?, ?, ?, ?)
                    """, id, drawId, reasonCode, Timestamp.from(now));
            return true;
        }
        return false;
    }
}
