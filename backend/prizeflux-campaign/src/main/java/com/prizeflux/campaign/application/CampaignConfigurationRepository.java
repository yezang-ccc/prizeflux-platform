package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.model.Prize;

import java.util.List;
import java.util.Optional;

/** 配置写入和冻结所需的数据库边界；调用者持有事务。 */
public interface CampaignConfigurationRepository {
    record DraftConfiguration(int dailyLimit, List<Prize> prizes) {
    }

    Optional<String> lockCampaignStatus(long campaignId);

    /** 旧运营活动没有 owner；直接发布路径只能锁定并读取这种活动。 */
    Optional<String> lockLegacyCampaignStatus(long campaignId);

    Optional<String> lockOwnedCampaignStatus(long campaignId, long ownerUserId);

    void replaceDraft(long campaignId, int dailyLimit, List<Prize> prizes);

    Optional<DraftConfiguration> findDraft(long campaignId);

    /** 读取当前版本的草稿或已发布配置；查询不得锁行、改状态或返回实时库存。 */
    Optional<DraftConfiguration> findConfiguration(long campaignId);

    void publishVersion(long campaignId);
}
