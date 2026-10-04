package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.domain.model.CampaignStatus;
import com.prizeflux.campaign.domain.model.Prize;
import com.prizeflux.campaign.domain.repository.CampaignRepository;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 草稿配置与发布冻结；HTTP 运营权限检查由未来 D8 入口提供。 */
@Service
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class CampaignConfigurationService {
    public record DraftView(int dailyLimit, List<Prize> prizes) {
        public DraftView {
            prizes = List.copyOf(prizes);
        }
    }

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
        CampaignConfigurationValidator.validate(campaignId, dailyLimit, prizes);
        List<Prize> frozenInput = List.copyOf(prizes);
        transactions.executeWithoutResult(status -> {
            requireDraft(campaignId);
            configurationRepository.replaceDraft(campaignId, dailyLimit, frozenInput);
        });
    }

    public void configureOwnedDraft(long campaignId, long ownerUserId, int dailyLimit, List<Prize> prizes) {
        List<Prize> frozenInput = List.copyOf(prizes);
        transactions.executeWithoutResult(status -> {
            String current = configurationRepository.lockOwnedCampaignStatus(campaignId, ownerUserId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (!"DRAFT".equals(current) && !"REJECTED".equals(current)) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
            }
            CampaignConfigurationValidator.validate(campaignId, dailyLimit, frozenInput);
            configurationRepository.replaceDraft(campaignId, dailyLimit, frozenInput);
            if ("REJECTED".equals(current)
                    && !campaignRepository.updateStatus(campaignId, CampaignStatus.REJECTED, CampaignStatus.DRAFT)) {
                throw new BusinessException(ErrorCode.CONFLICT);
            }
        });
    }

    public void submitOwnedForReview(long campaignId, long ownerUserId) {
        if (campaignId <= 0 || ownerUserId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        transactions.executeWithoutResult(status -> {
            String current = configurationRepository.lockOwnedCampaignStatus(campaignId, ownerUserId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (!"DRAFT".equals(current)) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
            }
            CampaignConfigurationRepository.DraftConfiguration configured = configurationRepository
                    .findDraft(campaignId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION));
            CampaignConfigurationValidator.validate(campaignId, configured.dailyLimit(), configured.prizes());
            if (!campaignRepository.submitOwnedForReview(campaignId, ownerUserId)) {
                throw new BusinessException(ErrorCode.CONFLICT);
            }
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
            CampaignConfigurationValidator.validate(campaignId, configured.dailyLimit(), configured.prizes());
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

}
