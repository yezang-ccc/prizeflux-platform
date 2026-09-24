package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.model.Prize;

import java.util.List;
import java.util.Optional;

/** 配置写入和冻结所需的数据库边界；调用者持有事务。 */
public interface CampaignConfigurationRepository {
    record DraftConfiguration(int dailyLimit, List<Prize> prizes) {
    }

    Optional<String> lockCampaignStatus(long campaignId);

    void replaceDraft(long campaignId, int dailyLimit, List<Prize> prizes);

    Optional<DraftConfiguration> findDraft(long campaignId);

    void publishVersion(long campaignId);
}
