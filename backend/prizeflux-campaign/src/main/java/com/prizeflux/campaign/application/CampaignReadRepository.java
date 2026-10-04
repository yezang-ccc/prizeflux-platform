package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.model.Campaign;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Campaign 查询用例专用的只读端口；before 游标按 ID 降序分页。 */
public interface CampaignReadRepository {
    List<Campaign> findAvailable(Instant now, Long beforeId, int limit);

    List<Campaign> findAll(Long beforeId, int limit);

    List<Campaign> findOwned(long ownerUserId, Long beforeId, int limit);

    Optional<Campaign> findOwnedById(long campaignId, long ownerUserId);
}
