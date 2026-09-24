package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.draw.DrawCandidate;
import com.prizeflux.campaign.domain.draw.LinearWeightDrawStrategy;
import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.domain.model.CampaignStatus;
import com.prizeflux.campaign.domain.model.Prize;
import com.prizeflux.campaign.domain.model.Probability;
import com.prizeflux.campaign.domain.repository.CampaignRepository;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/** 草稿配置与发布冻结；HTTP 运营权限检查由未来 D8 入口提供。 */
@Service
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class CampaignConfigurationService {
    private final CampaignConfigurationRepository configurationRepository;
    private final CampaignRepository campaignRepository;
    private final TransactionTemplate transactions;

    public CampaignConfigurationService(CampaignConfigurationRepository configurationRepository,
            CampaignRepository campaignRepository, PlatformTransactionManager transactionManager) {
        this.configurationRepository = configurationRepository;
        this.campaignRepository = campaignRepository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void configureDraft(long campaignId, int dailyLimit, List<Prize> prizes) {
        validate(campaignId, dailyLimit, prizes);
        List<Prize> frozenInput = List.copyOf(prizes);
        transactions.executeWithoutResult(status -> {
            requireDraft(campaignId);
            configurationRepository.replaceDraft(campaignId, dailyLimit, frozenInput);
        });
    }

    public Campaign publish(long campaignId) {
        if (campaignId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        transactions.executeWithoutResult(status -> {
            requireDraft(campaignId);
            CampaignConfigurationRepository.DraftConfiguration configured = configurationRepository
                    .findDraft(campaignId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION));
            validate(campaignId, configured.dailyLimit(), configured.prizes());
            configurationRepository.publishVersion(campaignId);
            if (!campaignRepository.updateStatus(
                    campaignId, CampaignStatus.DRAFT, CampaignStatus.PUBLISHED)) {
                throw new BusinessException(ErrorCode.CONFLICT);
            }
        });
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireDraft(long campaignId) {
        String current = configurationRepository.lockCampaignStatus(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!"DRAFT".equals(current)) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
    }

    private static void validate(long campaignId, int dailyLimit, List<Prize> prizes) {
        if (campaignId <= 0 || dailyLimit <= 0 || prizes == null || prizes.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        List<DrawCandidate> candidates = new ArrayList<>(prizes.size());
        for (Prize prize : prizes) {
            if (prize == null || prize.campaignId() != campaignId) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            }
            candidates.add(new DrawCandidate(prize.id(), Probability.of(prize.probability())));
        }
        new LinearWeightDrawStrategy(candidates);
    }
}
