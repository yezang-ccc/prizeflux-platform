package com.prizeflux.campaign.infrastructure.persistence;

import com.prizeflux.campaign.application.CampaignConfigurationRepository;
import com.prizeflux.campaign.domain.model.Prize;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** V2 配置表的 JDBC 适配器；发布必须由应用服务在一个事务内调用。 */
@Repository
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcCampaignConfigurationRepository implements CampaignConfigurationRepository {
    private final JdbcTemplate jdbc;

    public JdbcCampaignConfigurationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> lockCampaignStatus(long campaignId) {
        return jdbc.query("SELECT status FROM campaign WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString("status"), campaignId).stream().findFirst();
    }

    @Override
    public Optional<String> lockLegacyCampaignStatus(long campaignId) {
        return jdbc.query("SELECT status FROM campaign WHERE id = ? AND owner_user_id IS NULL FOR UPDATE",
                (rs, row) -> rs.getString("status"), campaignId).stream().findFirst();
    }

    @Override
    public Optional<String> lockOwnedCampaignStatus(long campaignId, long ownerUserId) {
        return jdbc.query("SELECT status FROM campaign WHERE id = ? AND owner_user_id = ? FOR UPDATE",
                (rs, row) -> rs.getString("status"), campaignId, ownerUserId).stream().findFirst();
    }

    @Override
    public void replaceDraft(long campaignId, int dailyLimit, List<Prize> prizes) {
        jdbc.update("""
                INSERT INTO campaign_config_version (campaign_id, config_version, daily_limit, state)
                VALUES (?, 1, ?, 'DRAFT')
                ON DUPLICATE KEY UPDATE daily_limit = ?
                """, campaignId, dailyLimit, dailyLimit);
        jdbc.update("DELETE FROM prize_inventory WHERE campaign_id = ? AND config_version = 1", campaignId);
        jdbc.update("DELETE FROM prize_config WHERE campaign_id = ? AND config_version = 1", campaignId);
        for (Prize prize : prizes) {
            jdbc.update("""
                    INSERT INTO prize_config (campaign_id, config_version, prize_id,
                                              name, probability, total_stock)
                    VALUES (?, 1, ?, ?, ?, ?)
                    """, campaignId, prize.id(), prize.name(), prize.probability(), prize.totalStock());
        }
    }

    @Override
    public Optional<DraftConfiguration> findDraft(long campaignId) {
        return findConfiguration(campaignId, "DRAFT");
    }

    @Override
    public Optional<DraftConfiguration> findConfiguration(long campaignId) {
        List<String> states = jdbc.query("""
                SELECT state FROM campaign_config_version
                WHERE campaign_id = ? AND config_version = 1
                """, (rs, row) -> rs.getString("state"), campaignId);
        if (states.isEmpty()) {
            return Optional.empty();
        }
        return findConfiguration(campaignId, states.get(0));
    }

    private Optional<DraftConfiguration> findConfiguration(long campaignId, String state) {
        List<Integer> limits = jdbc.query("""
                SELECT daily_limit FROM campaign_config_version
                WHERE campaign_id = ? AND config_version = 1 AND state = ?
                """, (rs, row) -> rs.getInt("daily_limit"), campaignId, state);
        if (limits.isEmpty()) {
            return Optional.empty();
        }
        List<Prize> prizes = jdbc.query("""
                SELECT prize_id, name, probability, total_stock
                FROM prize_config WHERE campaign_id = ? AND config_version = 1
                ORDER BY prize_id
                """, (rs, row) -> new Prize(
                rs.getLong("prize_id"), campaignId, rs.getString("name"),
                rs.getInt("total_stock"), rs.getBigDecimal("probability")), campaignId);
        return Optional.of(new DraftConfiguration(limits.get(0), List.copyOf(prizes)));
    }

    @Override
    public void publishVersion(long campaignId) {
        jdbc.update("""
                INSERT INTO prize_inventory (campaign_id, config_version, prize_id, remaining_stock)
                SELECT campaign_id, config_version, prize_id, total_stock
                FROM prize_config WHERE campaign_id = ? AND config_version = 1
                """, campaignId);
        int changed = jdbc.update("""
                UPDATE campaign_config_version
                SET state = 'PUBLISHED', published_at = UTC_TIMESTAMP(6)
                WHERE campaign_id = ? AND config_version = 1 AND state = 'DRAFT'
                """, campaignId);
        if (changed != 1) {
            throw new IllegalStateException("Configuration publication failed");
        }
    }
}
