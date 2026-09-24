package com.prizeflux.campaign.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.application.DrawHistoryEntry;
import com.prizeflux.campaign.application.DrawOutcome;
import com.prizeflux.campaign.application.DrawRepository;
import com.prizeflux.campaign.application.DrawResult;
import com.prizeflux.campaign.application.DuplicateDrawRequestException;
import com.prizeflux.campaign.domain.draw.DrawCandidate;
import com.prizeflux.campaign.domain.model.Probability;
import com.prizeflux.contract.event.PrizeFulfillmentEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 将抽奖事务端口映射到同一 MySQL 连接；业务判断保留在应用层。 */
@Repository
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcDrawRepository implements DrawRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcDrawRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<StoredDraw> findByKey(long userId, String requestKey) {
        List<StoredDraw> found = jdbc.query("""
                SELECT r.request_fingerprint, r.status,
                       d.id AS result_id, d.campaign_id, d.user_id,
                       d.outcome, d.prize_id, d.fulfillment_status
                FROM draw_request r
                LEFT JOIN draw_result d ON d.request_id = r.id
                WHERE r.operation = 'DRAW' AND r.user_id = ? AND r.request_key = ?
                """, (rs, row) -> {
            long resultId = rs.getLong("result_id");
            DrawResult result = rs.wasNull() ? null : new DrawResult(
                    resultId,
                    rs.getLong("campaign_id"),
                    rs.getLong("user_id"),
                    DrawOutcome.valueOf(rs.getString("outcome")),
                    rs.getObject("prize_id", Long.class),
                    rs.getString("fulfillment_status"));
            return new StoredDraw(rs.getBytes("request_fingerprint"),
                    "COMPLETED".equals(rs.getString("status")), result);
        }, userId, requestKey);
        return found.stream().findFirst();
    }

    @Override
    public List<DrawHistoryEntry> findByUser(long userId, long beforeId, int limit) {
        // 个人历史量级小；idx_draw_history(user_id, created_at, id) 先按 user_id 收窄，再按 id 倒序。
        return jdbc.query("""
                SELECT id, campaign_id, outcome, prize_id, fulfillment_status, created_at
                FROM draw_result
                WHERE user_id = ? AND id < ?
                ORDER BY id DESC
                LIMIT ?
                """, (rs, row) -> new DrawHistoryEntry(
                rs.getLong("id"),
                rs.getLong("campaign_id"),
                DrawOutcome.valueOf(rs.getString("outcome")),
                rs.getObject("prize_id", Long.class),
                rs.getString("fulfillment_status"),
                rs.getTimestamp("created_at").toInstant()), userId, beforeId, limit);
    }

    @Override
    public Optional<DrawHistoryEntry> findById(long userId, long id) {
        List<DrawHistoryEntry> found = jdbc.query("""
                SELECT id, campaign_id, outcome, prize_id, fulfillment_status, created_at
                FROM draw_result
                WHERE user_id = ? AND id = ?
                """, (rs, row) -> new DrawHistoryEntry(
                rs.getLong("id"),
                rs.getLong("campaign_id"),
                DrawOutcome.valueOf(rs.getString("outcome")),
                rs.getObject("prize_id", Long.class),
                rs.getString("fulfillment_status"),
                rs.getTimestamp("created_at").toInstant()), userId, id);
        return found.stream().findFirst();
    }

    @Override
    public Optional<ConfigSnapshot> lockPublishedConfig(long campaignId) {
        List<ConfigSnapshot> found = jdbc.query("""
                SELECT c.status, c.starts_at, c.ends_at,
                       v.config_version, v.daily_limit
                FROM campaign c
                JOIN campaign_config_version v ON v.campaign_id = c.id
                WHERE c.id = ? AND v.state = 'PUBLISHED'
                ORDER BY v.config_version DESC
                LIMIT 1 FOR SHARE
                """, (rs, row) -> new ConfigSnapshot(
                rs.getInt("config_version"),
                rs.getInt("daily_limit"),
                rs.getString("status"),
                rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(),
                List.of()), campaignId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ConfigSnapshot row = found.get(0);
        return Optional.of(new ConfigSnapshot(row.version(), row.dailyLimit(), row.campaignStatus(),
                row.startsAt(), row.endsAt(), loadCandidates(campaignId, row.version())));
    }

    private List<DrawCandidate> loadCandidates(long campaignId, int configVersion) {
        return jdbc.query("""
                SELECT prize_id, probability
                FROM prize_config
                WHERE campaign_id = ? AND config_version = ?
                ORDER BY prize_id
                """, (rs, row) -> new DrawCandidate(
                rs.getLong("prize_id"), Probability.of(rs.getBigDecimal("probability"))),
                campaignId, configVersion);
    }

    @Override
    public long insertRequest(long userId, long campaignId, int configVersion,
            String requestKey, byte[] fingerprint) {
        KeyHolder key = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO draw_request (operation, user_id, campaign_id, config_version,
                                                  request_key, request_fingerprint, status)
                        VALUES ('DRAW', ?, ?, ?, ?, ?, 'PENDING')
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, userId);
                statement.setLong(2, campaignId);
                statement.setInt(3, configVersion);
                statement.setString(4, requestKey);
                statement.setBytes(5, fingerprint);
                return statement;
            }, key);
        } catch (DuplicateKeyException duplicate) {
            throw new DuplicateDrawRequestException(duplicate);
        }
        return requiredKey(key);
    }

    @Override
    public boolean tryConsumeQuota(long campaignId, long userId, LocalDate businessDay, int limit) {
        jdbc.update("""
                INSERT INTO user_campaign_quota (campaign_id, user_id, business_day, used_count)
                VALUES (?, ?, ?, 0)
                ON DUPLICATE KEY UPDATE used_count = used_count
                """, campaignId, userId, businessDay);
        int changed = jdbc.update("""
                UPDATE user_campaign_quota
                SET used_count = used_count + 1
                WHERE campaign_id = ? AND user_id = ? AND business_day = ?
                  AND used_count < ?
                """, campaignId, userId, businessDay, limit);
        if (changed > 1) {
            throw new IllegalStateException("Quota update affected more than one row");
        }
        return changed == 1;
    }

    @Override
    public DrawResult insertResult(long requestId, long campaignId, int configVersion,
            long userId, DrawOutcome outcome, Long prizeId) {
        String fulfillment = outcome == DrawOutcome.WON ? "PENDING" : null;
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO draw_result (request_id, campaign_id, config_version,
                                             user_id, prize_id, outcome, fulfillment_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, requestId);
            statement.setLong(2, campaignId);
            statement.setInt(3, configVersion);
            statement.setLong(4, userId);
            if (prizeId == null) {
                statement.setNull(5, Types.BIGINT);
            } else {
                statement.setLong(5, prizeId);
            }
            statement.setString(6, outcome.name());
            if (fulfillment == null) {
                statement.setNull(7, Types.VARCHAR);
            } else {
                statement.setString(7, fulfillment);
            }
            return statement;
        }, key);
        return new DrawResult(requiredKey(key), campaignId, userId, outcome, prizeId, fulfillment);
    }

    @Override
    public void completeRequest(long requestId) {
        int changed = jdbc.update("""
                UPDATE draw_request
                SET status = 'COMPLETED', completed_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND status = 'PENDING'
                """, requestId);
        if (changed != 1) {
            throw new IllegalStateException("Draw request completion failed");
        }
    }

    @Override
    public void insertOutbox(PrizeFulfillmentEvent event) {
        String payload;
        try {
            payload = json.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize fulfillment event", exception);
        }
        jdbc.update("""
                INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id,
                                          event_type, schema_version, payload, status, available_at)
                VALUES (?, 'DRAW', ?, 'PrizeFulfillmentRequested', ?, ?, 'PENDING', UTC_TIMESTAMP(6))
                """, event.eventId(), event.drawId(), event.schemaVersion(), payload);
    }

    private static long requiredKey(KeyHolder key) {
        Number generated = key.getKey();
        if (generated == null || generated.longValue() <= 0) {
            throw new IllegalStateException("Database did not generate a positive ID");
        }
        return generated.longValue();
    }
}
