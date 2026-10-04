package com.prizeflux.campaign.domain.repository;

import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.domain.model.CampaignStatus;

import java.util.Optional;
import java.time.Instant;

/** 领域层定义的活动持久化端口，不暴露MyBatis或数据库行对象。 */
public interface CampaignRepository {

    void save(Campaign campaign);

    Campaign createOwned(String name, Instant startsAt, Instant endsAt, long ownerUserId);

    Optional<Campaign> findOwnedById(long id, long ownerUserId);

    boolean updateOwnedDetails(long id, long ownerUserId, String name, Instant startsAt, Instant endsAt);

    boolean submitOwnedForReview(long id, long ownerUserId);

    Optional<Campaign> findById(long id);

    boolean updateStatus(long id, CampaignStatus expectedStatus, CampaignStatus targetStatus);
}
