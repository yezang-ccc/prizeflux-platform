package com.prizeflux.campaign.controller.dto;

import com.prizeflux.campaign.application.CampaignOwnershipService;
import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.application.CampaignReviewRepository;
import com.prizeflux.campaign.domain.model.CampaignStatus;

import java.time.Instant;

/** 创建者可见的活动与最近审核反馈，不暴露审核员内部数据。 */
public record OwnedCampaignResponse(long id, String name, Instant startsAt, Instant endsAt,
        CampaignStatus status, String reviewComment, Instant reviewedAt) {
    public static OwnedCampaignResponse from(Campaign campaign) {
        return new OwnedCampaignResponse(campaign.id(), campaign.name(), campaign.startsAt(),
                campaign.endsAt(), campaign.status(), null, null);
    }

    public static OwnedCampaignResponse from(CampaignOwnershipService.OwnedCampaign owned) {
        Campaign campaign = owned.campaign();
        CampaignReviewRepository.Decision decision = owned.latestDecision();
        return new OwnedCampaignResponse(campaign.id(), campaign.name(), campaign.startsAt(),
                campaign.endsAt(), campaign.status(), decision == null ? null : decision.comment(),
                decision == null ? null : decision.decidedAt());
    }
}
