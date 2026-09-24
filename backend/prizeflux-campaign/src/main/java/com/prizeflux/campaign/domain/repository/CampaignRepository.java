package com.prizeflux.campaign.domain.repository;

import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.domain.model.CampaignStatus;

import java.util.Optional;

/** 领域层定义的活动持久化端口，不暴露MyBatis或数据库行对象。 */
public interface CampaignRepository {

    void save(Campaign campaign);

    Optional<Campaign> findById(long id);

    boolean updateStatus(long id, CampaignStatus expectedStatus, CampaignStatus targetStatus);
}
