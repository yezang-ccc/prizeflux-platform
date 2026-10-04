package com.prizeflux.campaign.infrastructure.persistence;

import com.prizeflux.campaign.application.CampaignReadRepository;
import com.prizeflux.campaign.domain.model.Campaign;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Campaign 只读分页查询适配器；状态及时间条件集中在 SQL。 */
@Repository
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class MyBatisCampaignReadRepository implements CampaignReadRepository {
    private final CampaignMapper mapper;

    public MyBatisCampaignReadRepository(CampaignMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<Campaign> findAvailable(Instant now, Long beforeId, int limit) {
        return mapper.findAvailable(now, beforeId, limit).stream()
                .map(MyBatisCampaignRepository::restoreDomainCampaign)
                .toList();
    }

    @Override
    public List<Campaign> findAll(Long beforeId, int limit) {
        return mapper.findAll(beforeId, limit).stream()
                .map(MyBatisCampaignRepository::restoreDomainCampaign)
                .toList();
    }

    @Override
    public List<Campaign> findOwned(long ownerUserId, Long beforeId, int limit) {
        return mapper.findOwned(ownerUserId, beforeId, limit).stream()
                .map(MyBatisCampaignRepository::restoreDomainCampaign)
                .toList();
    }

    @Override
    public java.util.Optional<Campaign> findOwnedById(long campaignId, long ownerUserId) {
        return java.util.Optional.ofNullable(mapper.findOwnedById(campaignId, ownerUserId))
                .map(MyBatisCampaignRepository::restoreDomainCampaign);
    }
}
