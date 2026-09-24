package com.prizeflux.campaign.infrastructure.persistence;

import com.prizeflux.campaign.application.OutboxEvent;
import com.prizeflux.campaign.application.OutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 用同一 MySQL 连接实现 Outbox 领取/标记；所有回写都以领取标识为条件，避免过期 Relay 覆盖新租约。 */
@Repository
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcOutboxRepository implements OutboxRepository {
    private final JdbcTemplate jdbc;

    public JdbcOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OutboxEvent> claim(int limit, String leaseToken, Instant leaseUntil, Instant now) {
        // 单表 UPDATE 支持 ORDER BY ... LIMIT，原子领取；随后按本次租约标识读回这批事件。
        jdbc.update("""
                UPDATE outbox_event
                SET status = 'LEASED', lease_token = ?, lease_until = ?, attempts = attempts + 1
                WHERE status = 'PENDING' AND available_at <= ?
                ORDER BY available_at, event_id
                LIMIT ?
                """, leaseToken, Timestamp.from(leaseUntil), Timestamp.from(now), limit);
        return jdbc.query("""
                SELECT event_id, event_type, aggregate_id, schema_version, payload, attempts
                FROM outbox_event
                WHERE status = 'LEASED' AND lease_token = ?
                ORDER BY event_id
                """, (rs, row) -> new OutboxEvent(
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getLong("aggregate_id"),
                rs.getInt("schema_version"),
                rs.getString("payload"),
                rs.getInt("attempts")), leaseToken);
    }

    @Override
    public int releaseExpired(Instant now) {
        return jdbc.update("""
                UPDATE outbox_event
                SET status = 'PENDING', lease_token = NULL, lease_until = NULL
                WHERE status = 'LEASED' AND lease_until < ?
                """, Timestamp.from(now));
    }

    @Override
    public void markSent(String eventId, String leaseToken) {
        jdbc.update("""
                UPDATE outbox_event
                SET status = 'SENT', sent_at = UTC_TIMESTAMP(6), lease_token = NULL, lease_until = NULL
                WHERE event_id = ? AND lease_token = ? AND status = 'LEASED'
                """, eventId, leaseToken);
    }

    @Override
    public void release(String eventId, String leaseToken) {
        jdbc.update("""
                UPDATE outbox_event
                SET status = 'PENDING', lease_token = NULL, lease_until = NULL
                WHERE event_id = ? AND lease_token = ? AND status = 'LEASED'
                """, eventId, leaseToken);
    }

    @Override
    public void markDead(String eventId, String leaseToken) {
        jdbc.update("""
                UPDATE outbox_event
                SET status = 'DEAD', lease_token = NULL, lease_until = NULL
                WHERE event_id = ? AND lease_token = ? AND status = 'LEASED'
                """, eventId, leaseToken);
    }
}
