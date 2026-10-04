package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.domain.model.CampaignStatus;
import com.prizeflux.campaign.domain.model.Prize;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/** 按服务端身份执行活动查询授权，并为 HTTP 提供稳定的只读快照。 */
@Service
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
@Transactional(readOnly = true)
public class CampaignReadService {
    public record CursorPage<T>(List<T> items, Long nextCursor) {
        public CursorPage {
            items = List.copyOf(items);
        }
    }

    public record PrizeView(long id, String name, java.math.BigDecimal probability) {
    }

    public record ConfigurationView(int dailyLimit, List<Prize> prizes) {
        public ConfigurationView {
            prizes = List.copyOf(prizes);
        }
    }

    private final CampaignUseCase campaigns;
    private final CampaignReadRepository readRepository;
    private final CampaignConfigurationRepository configurationRepository;
    private final ActorProvider actorProvider;
    private final Clock clock;

    public CampaignReadService(CampaignUseCase campaigns, CampaignReadRepository readRepository,
            CampaignConfigurationRepository configurationRepository, ActorProvider actorProvider, Clock clock) {
        this.campaigns = campaigns;
        this.readRepository = readRepository;
        this.configurationRepository = configurationRepository;
        this.actorProvider = actorProvider;
        this.clock = clock;
    }

    public CursorPage<Campaign> availableCampaigns(Long beforeId, Integer requestedLimit) {
        requireRole(AppUser.Role.USER);
        int limit = validatePage(beforeId, requestedLimit);
        return page(readRepository.findAvailable(clock.instant(), beforeId, limit + 1), limit);
    }

    public CursorPage<Campaign> operatorCampaigns(Long beforeId, Integer requestedLimit) {
        requireRole(AppUser.Role.OPERATOR);
        int limit = validatePage(beforeId, requestedLimit);
        return page(readRepository.findAll(beforeId, limit + 1), limit);
    }

    public Campaign campaign(long campaignId) {
        AuthenticatedActor actor = actorProvider.currentActor();
        Campaign campaign = campaigns.get(campaignId);
        if (actor.role() == AppUser.Role.USER && campaign.status() != CampaignStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return campaign;
    }

    public List<PrizeView> prizes(long campaignId) {
        Campaign campaign = campaign(campaignId);
        return configurationRepository.findConfiguration(campaignId)
                .map(configuration -> configuration.prizes().stream()
                        .map(prize -> new PrizeView(prize.id(), prize.name(), prize.probability()))
                        .toList())
                .orElseGet(List::of);
    }

    public ConfigurationView operatorConfiguration(long campaignId) {
        requireRole(AppUser.Role.OPERATOR);
        campaigns.get(campaignId);
        return configurationRepository.findConfiguration(campaignId)
                .map(configuration -> new ConfigurationView(
                        configuration.dailyLimit(), configuration.prizes()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireRole(AppUser.Role expected) {
        if (actorProvider.currentActor().role() != expected) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private static int validatePage(Long beforeId, Integer requestedLimit) {
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if ((beforeId != null && beforeId <= 0) || limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        return limit;
    }

    private static CursorPage<Campaign> page(List<Campaign> fetched, int limit) {
        boolean hasMore = fetched.size() > limit;
        List<Campaign> items = hasMore ? fetched.subList(0, limit) : fetched;
        Long nextCursor = hasMore ? items.get(items.size() - 1).id() : null;
        return new CursorPage<>(items, nextCursor);
    }
}
