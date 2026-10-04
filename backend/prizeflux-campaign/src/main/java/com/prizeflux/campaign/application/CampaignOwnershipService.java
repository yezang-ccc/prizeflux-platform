package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.domain.model.CampaignStatus;
import com.prizeflux.campaign.domain.model.Prize;
import com.prizeflux.campaign.domain.repository.CampaignRepository;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** USER 自有活动用例；每个读写都以服务端 actor 和 owner-filtered SQL 为边界。 */
@Service
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
@Transactional(readOnly = true)
public class CampaignOwnershipService {
    public record PrizeInput(long id, String name, int totalStock, java.math.BigDecimal probability) {
    }

    public record OwnedCampaign(Campaign campaign, CampaignReviewRepository.Decision latestDecision) {
    }

    public record CursorPage<T>(List<T> items, Long nextCursor) {
        public CursorPage {
            items = List.copyOf(items);
        }
    }

    private final CampaignUseCase campaigns;
    private final CampaignRepository campaignRepository;
    private final CampaignReadRepository readRepository;
    private final CampaignConfigurationRepository configurationRepository;
    private final CampaignConfigurationService configurationService;
    private final CampaignReviewRepository reviewRepository;
    private final ActorProvider actorProvider;

    public CampaignOwnershipService(CampaignUseCase campaigns, CampaignRepository campaignRepository,
            CampaignReadRepository readRepository, CampaignConfigurationRepository configurationRepository,
            CampaignConfigurationService configurationService, CampaignReviewRepository reviewRepository,
            ActorProvider actorProvider) {
        this.campaigns = campaigns;
        this.campaignRepository = campaignRepository;
        this.readRepository = readRepository;
        this.configurationRepository = configurationRepository;
        this.configurationService = configurationService;
        this.reviewRepository = reviewRepository;
        this.actorProvider = actorProvider;
    }

    @Transactional
    public Campaign create(String name, Instant startsAt, Instant endsAt) {
        AuthenticatedActor actor = requireUser();
        return campaigns.createOwned(name, startsAt, endsAt, actor.userId());
    }

    public CursorPage<Campaign> list(Long beforeId, Integer requestedLimit) {
        AuthenticatedActor actor = requireUser();
        int limit = validatePage(beforeId, requestedLimit);
        List<Campaign> fetched = readRepository.findOwned(actor.userId(), beforeId, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<Campaign> items = hasMore ? fetched.subList(0, limit) : fetched;
        Long nextCursor = hasMore ? items.get(items.size() - 1).id() : null;
        return new CursorPage<>(items, nextCursor);
    }

    public OwnedCampaign get(long campaignId) {
        AuthenticatedActor actor = requireUser();
        Campaign campaign = owned(campaignId, actor.userId());
        CampaignReviewRepository.Decision latest = reviewRepository
                .findLatestDecision(campaignId, actor.userId()).orElse(null);
        return new OwnedCampaign(campaign, latest);
    }

    @Transactional
    public Campaign update(long campaignId, String name, Instant startsAt, Instant endsAt) {
        AuthenticatedActor actor = requireUser();
        Campaign current = owned(campaignId, actor.userId());
        Campaign replacement = current.edit(name, startsAt, endsAt);
        if (!campaignRepository.updateOwnedDetails(campaignId, actor.userId(),
                replacement.name(), replacement.startsAt(), replacement.endsAt())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        return replacement;
    }

    public CampaignConfigurationService.DraftView configuration(long campaignId) {
        AuthenticatedActor actor = requireUser();
        Campaign campaign = owned(campaignId, actor.userId());
        if (campaign.status() != CampaignStatus.DRAFT && campaign.status() != CampaignStatus.REJECTED) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        CampaignConfigurationRepository.DraftConfiguration configuration = configurationRepository
                .findDraft(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return new CampaignConfigurationService.DraftView(configuration.dailyLimit(), configuration.prizes());
    }

    @Transactional
    public void configure(long campaignId, int dailyLimit, List<com.prizeflux.campaign.domain.model.Prize> prizes) {
        AuthenticatedActor actor = requireUser();
        configurationService.configureOwnedDraft(campaignId, actor.userId(), dailyLimit, prizes);
    }

    @Transactional
    public void submit(long campaignId) {
        AuthenticatedActor actor = requireUser();
        configurationService.submitOwnedForReview(campaignId, actor.userId());
    }

    public CampaignDraftValidation validateProposal(String name, Instant startsAt, Instant endsAt,
            int dailyLimit, List<PrizeInput> prizes) {
        requireUser();
        List<CampaignDraftValidation.Finding> findings = new ArrayList<>();
        if (name == null || name.isBlank() || name.length() > 100) {
            findings.add(new CampaignDraftValidation.Finding(
                    "CAMPAIGN_NAME_INVALID", "name", "活动名称不能为空且不能超过 100 个字符。"));
        }
        if (startsAt == null) {
            findings.add(new CampaignDraftValidation.Finding(
                    "CAMPAIGN_START_REQUIRED", "startsAt", "活动开始时间不能为空。"));
        }
        if (endsAt == null) {
            findings.add(new CampaignDraftValidation.Finding(
                    "CAMPAIGN_END_REQUIRED", "endsAt", "活动结束时间不能为空。"));
        }
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            findings.add(new CampaignDraftValidation.Finding(
                    "CAMPAIGN_TIME_WINDOW_INVALID", "endsAt", "活动结束时间必须晚于开始时间。"));
        }
        if (dailyLimit <= 0) {
            findings.add(new CampaignDraftValidation.Finding(
                    "CAMPAIGN_DAILY_LIMIT_INVALID", "dailyLimit", "每日参与次数必须为正数。"));
        }
        if (prizes == null || prizes.isEmpty()) {
            findings.add(new CampaignDraftValidation.Finding(
                    "CAMPAIGN_PRIZES_REQUIRED", "prizes", "至少需要配置一个奖品。"));
        } else {
            Set<Long> prizeIds = new HashSet<>();
            java.math.BigDecimal probabilityTotal = java.math.BigDecimal.ZERO;
            boolean sumIsComparable = true;
            for (int index = 0; index < prizes.size(); index++) {
                PrizeInput prize = prizes.get(index);
                String field = "prizes[" + index + "]";
                if (prize == null) {
                    findings.add(new CampaignDraftValidation.Finding(
                            "CAMPAIGN_PRIZE_REQUIRED", field, "奖品项不能为空。"));
                    sumIsComparable = false;
                    continue;
                }
                if (prize.id() <= 0) {
                    findings.add(new CampaignDraftValidation.Finding(
                            "CAMPAIGN_PRIZE_ID_INVALID", field + ".id", "奖品编号必须为正数。"));
                } else if (!prizeIds.add(prize.id())) {
                    findings.add(new CampaignDraftValidation.Finding(
                            "CAMPAIGN_PRIZE_ID_DUPLICATE", field + ".id", "奖品编号不能重复。"));
                }
                if (prize.name() == null || prize.name().isBlank() || prize.name().length() > 100) {
                    findings.add(new CampaignDraftValidation.Finding(
                            "CAMPAIGN_PRIZE_NAME_INVALID", field + ".name", "奖品名称不能为空且不能超过 100 个字符。"));
                }
                if (prize.totalStock() < 0) {
                    findings.add(new CampaignDraftValidation.Finding(
                            "CAMPAIGN_PRIZE_STOCK_INVALID", field + ".totalStock", "奖品库存不能为负数。"));
                }
                java.math.BigDecimal probability = prize.probability();
                if (probability == null || probability.signum() < 0
                        || probability.compareTo(java.math.BigDecimal.ONE) > 0) {
                    findings.add(new CampaignDraftValidation.Finding(
                            "CAMPAIGN_PRIZE_PROBABILITY_INVALID", field + ".probability",
                            "单项中奖概率必须在 0 到 1 之间。"));
                    sumIsComparable = false;
                } else if (probability.stripTrailingZeros().scale() > 6) {
                    findings.add(new CampaignDraftValidation.Finding(
                            "CAMPAIGN_PRIZE_PROBABILITY_PRECISION_UNSUPPORTED", field + ".probability",
                            "中奖概率最多支持 6 位有效小数。"));
                    sumIsComparable = false;
                } else {
                    probabilityTotal = probabilityTotal.add(probability);
                }
            }
            if (sumIsComparable && probabilityTotal.compareTo(java.math.BigDecimal.ONE) > 0) {
                findings.add(new CampaignDraftValidation.Finding(
                        "CAMPAIGN_PRIZE_PROBABILITY_TOTAL_EXCEEDED", "prizes",
                        "奖品概率总和不能超过 1；不足 1 的部分表示未中奖。"));
            }
        }

        // Keep the existing domain invariants as the final acceptance check for this preflight.
        if (findings.isEmpty()) {
            try {
                new Campaign(1, name, startsAt, endsAt);
                List<Prize> normalized = prizes.stream()
                        .map(prize -> new Prize(prize.id(), 1, prize.name(),
                                prize.totalStock(), prize.probability())).toList();
                CampaignConfigurationValidator.validate(1, dailyLimit, normalized);
            } catch (BusinessException | IllegalArgumentException exception) {
                findings.add(new CampaignDraftValidation.Finding(
                        "CAMPAIGN_DRAFT_RULES_REJECTED", "configuration",
                        "活动草案未通过服务端规则校验，请检查活动与奖品配置。"));
            }
        }
        return new CampaignDraftValidation(findings);
    }

    private Campaign owned(long campaignId, long ownerUserId) {
        if (campaignId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        return readRepository.findOwnedById(campaignId, ownerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private AuthenticatedActor requireUser() {
        AuthenticatedActor actor = actorProvider.currentActor();
        if (actor.role() != AppUser.Role.USER) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return actor;
    }

    private static int validatePage(Long beforeId, Integer requestedLimit) {
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if ((beforeId != null && beforeId <= 0) || limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        return limit;
    }
}
