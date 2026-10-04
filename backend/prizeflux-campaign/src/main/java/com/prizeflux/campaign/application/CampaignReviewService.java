package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.domain.model.CampaignStatus;
import com.prizeflux.campaign.domain.repository.CampaignRepository;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 运营活动审核；状态、冻结配置及追加审计记录在同一 MySQL 事务内提交。 */
@Service
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class CampaignReviewService {
    public record PendingReviewView(CampaignReviewRepository.PendingReview campaign) {
    }

    public record CursorPage(List<PendingReviewView> items, Long nextCursor) {
        public CursorPage {
            items = List.copyOf(items);
        }
    }

    private final CampaignReviewRepository reviewRepository;
    private final CampaignConfigurationRepository configurationRepository;
    private final CampaignRepository campaignRepository;
    private final ActorProvider actorProvider;
    private final TransactionTemplate transactions;

    public CampaignReviewService(CampaignReviewRepository reviewRepository,
            CampaignConfigurationRepository configurationRepository, CampaignRepository campaignRepository,
            ActorProvider actorProvider, PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.configurationRepository = configurationRepository;
        this.campaignRepository = campaignRepository;
        this.actorProvider = actorProvider;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public CursorPage pending(Long beforeId, Integer requestedLimit) {
        requireOperator();
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if ((beforeId != null && beforeId <= 0) || limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        List<CampaignReviewRepository.PendingReview> fetched = reviewRepository.findPending(beforeId, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<CampaignReviewRepository.PendingReview> items = hasMore
                ? fetched.subList(0, limit) : fetched;
        Long nextCursor = hasMore ? items.get(items.size() - 1).campaignId() : null;
        return new CursorPage(items.stream().map(PendingReviewView::new).toList(), nextCursor);
    }

    public Campaign approve(long campaignId, String comment) {
        AuthenticatedActor operator = requireOperator();
        String normalizedComment = normalizeOptionalComment(comment);
        transactions.executeWithoutResult(status -> {
            requirePendingAndLock(campaignId);
            CampaignConfigurationRepository.DraftConfiguration configuration = configurationRepository
                    .findDraft(campaignId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION));
            CampaignConfigurationValidator.validate(campaignId,
                    configuration.dailyLimit(), configuration.prizes());
            configurationRepository.publishVersion(campaignId);
            if (!campaignRepository.updateStatus(campaignId,
                    CampaignStatus.PENDING_REVIEW, CampaignStatus.PUBLISHED)) {
                throw new BusinessException(ErrorCode.CONFLICT);
            }
            reviewRepository.recordDecision(campaignId, operator.userId(), "APPROVED", normalizedComment);
        });
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public Campaign reject(long campaignId, String comment) {
        AuthenticatedActor operator = requireOperator();
        if (campaignId <= 0 || comment == null || comment.trim().length() < 3
                || comment.trim().length() > 500) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        String normalizedComment = comment.trim();
        transactions.executeWithoutResult(status -> {
            requirePendingAndLock(campaignId);
            if (!campaignRepository.updateStatus(campaignId,
                    CampaignStatus.PENDING_REVIEW, CampaignStatus.REJECTED)) {
                throw new BusinessException(ErrorCode.CONFLICT);
            }
            reviewRepository.recordDecision(campaignId, operator.userId(), "REJECTED", normalizedComment);
        });
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requirePendingAndLock(long campaignId) {
        if (campaignId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        String current = configurationRepository.lockCampaignStatus(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!CampaignStatus.PENDING_REVIEW.name().equals(current)) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
    }

    private AuthenticatedActor requireOperator() {
        AuthenticatedActor actor = actorProvider.currentActor();
        if (actor.role() != AppUser.Role.OPERATOR) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return actor;
    }

    private static String normalizeOptionalComment(String comment) {
        String normalized = comment == null ? "" : comment.trim();
        if (normalized.length() > 500) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        return normalized;
    }
}
