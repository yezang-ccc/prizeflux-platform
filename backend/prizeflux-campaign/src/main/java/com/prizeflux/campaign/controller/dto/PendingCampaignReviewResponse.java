package com.prizeflux.campaign.controller.dto;

import com.prizeflux.campaign.application.CampaignReviewRepository;

import java.time.Instant;

public record PendingCampaignReviewResponse(long id, String name, Instant startsAt, Instant endsAt,
        long ownerUserId, String ownerUsername, Instant submittedAt) {
    public static PendingCampaignReviewResponse from(CampaignReviewRepository.PendingReview review) {
        return new PendingCampaignReviewResponse(review.campaignId(), review.name(), review.startsAt(),
                review.endsAt(), review.ownerUserId(), review.ownerUsername(), review.submittedAt());
    }
}
