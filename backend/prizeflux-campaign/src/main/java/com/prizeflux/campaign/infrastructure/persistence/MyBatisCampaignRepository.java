package com.prizeflux.campaign.infrastructure.persistence;

import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.domain.model.CampaignStatus;
import com.prizeflux.campaign.domain.repository.CampaignRepository;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 使用MyBatis实现领域端口，并负责数据库行与合法领域状态之间的转换。 */
@Repository
@ConditionalOnProperty(
        name = "prizeflux.persistence.campaign.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MyBatisCampaignRepository implements CampaignRepository {
    private final CampaignMapper campaignMapper;

    public MyBatisCampaignRepository(CampaignMapper campaignMapper) {
        this.campaignMapper = campaignMapper;
    }

    @Override
    public void save(Campaign campaign) {
        if (campaign == null) {
            throw new IllegalArgumentException("campaign must not be null");
        }

        CampaignPersistenceRecord record = new CampaignPersistenceRecord(
                campaign.id(),
                campaign.name(),
                campaign.startsAt(),
                campaign.endsAt(),
                campaign.status().name());
        int insertedRows;
        try {
            insertedRows = campaignMapper.insert(record);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        if (insertedRows != 1) {
            throw new IllegalStateException("Expected one inserted campaign row, but got " + insertedRows);
        }
    }

    @Override
    public Optional<Campaign> findById(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("campaign id must be positive");
        }
        return Optional.ofNullable(campaignMapper.findById(id))
                .map(MyBatisCampaignRepository::restoreDomainCampaign);
    }

    @Override
    public boolean updateStatus(long id, CampaignStatus expectedStatus, CampaignStatus targetStatus) {
        if (id <= 0) {
            throw new IllegalArgumentException("campaign id must be positive");
        }
        if (expectedStatus == null || targetStatus == null) {
            throw new IllegalArgumentException("campaign statuses must not be null");
        }

        int updatedRows = campaignMapper.updateStatus(id, expectedStatus.name(), targetStatus.name());
        return switch (updatedRows) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new IllegalStateException(
                    "Expected zero or one updated campaign row, but got " + updatedRows);
        };
    }

    private static Campaign restoreDomainCampaign(CampaignPersistenceRecord record) {
        Campaign draft = new Campaign(record.id(), record.name(), record.startsAt(), record.endsAt());
        if (record.status() == null) {
            throw new IllegalStateException("Persisted campaign status must not be null");
        }
        return switch (record.status()) {
            case "DRAFT" -> draft;
            case "PUBLISHED" -> draft.publish();
            case "CLOSED" -> draft.close();
            default -> throw new IllegalStateException("Unknown persisted campaign status: " + record.status());
        };
    }
}
