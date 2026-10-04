package com.prizeflux.campaign.infrastructure.persistence;

import com.prizeflux.campaign.application.CampaignReviewRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** MySQL 审核队列和只追加决策记录的适配器。 */
@Repository
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcCampaignReviewRepository implements CampaignReviewRepository {
    private final JdbcTemplate jdbc;

    public JdbcCampaignReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PendingReview> findPending(Long beforeId, int limit) {
        return jdbc.query("""
                SELECT c.id, c.name, c.starts_at, c.ends_at, c.owner_user_id,
                       u.username AS owner_username, c.submitted_at
                FROM campaign c
                JOIN app_user u ON u.id = c.owner_user_id
                WHERE c.status = 'PENDING_REVIEW'
                  AND c.owner_user_id IS NOT NULL
                """ + (beforeId == null ? "" : " AND c.id < ?") + " ORDER BY c.id DESC LIMIT ?",
                (rs, row) -> new PendingReview(rs.getLong("id"), rs.getString("name"),
                        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                        rs.getLong("owner_user_id"), rs.getString("owner_username"),
                        rs.getTimestamp("submitted_at").toInstant()),
                beforeId == null ? new Object[] {limit} : new Object[] {beforeId, limit});
    }

    @Override
    public Optional<Decision> findLatestDecision(long campaignId, long ownerUserId) {
        return jdbc.query("""
                SELECT d.campaign_id, d.operator_user_id, d.decision, d.comment, d.decided_at
                FROM campaign_review_decision d
                JOIN campaign c ON c.id = d.campaign_id
                WHERE d.campaign_id = ? AND c.owner_user_id = ?
                ORDER BY d.id DESC LIMIT 1
                """, (rs, row) -> new Decision(rs.getLong("campaign_id"), rs.getLong("operator_user_id"),
                rs.getString("decision"), rs.getString("comment"), rs.getTimestamp("decided_at").toInstant()),
                campaignId, ownerUserId).stream().findFirst();
    }

    @Override
    public void recordDecision(long campaignId, long operatorUserId, String decision, String comment) {
        jdbc.update("""
                INSERT INTO campaign_review_decision (campaign_id, operator_user_id, decision, comment)
                VALUES (?, ?, ?, ?)
                """, campaignId, operatorUserId, decision, comment);
    }
}
