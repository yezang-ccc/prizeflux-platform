package com.prizeflux.campaign.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 审核队列与追加式决策审计的持久化端口。 */
public interface CampaignReviewRepository {
    record PendingReview(long campaignId, String name, Instant startsAt, Instant endsAt,
            long ownerUserId, String ownerUsername, Instant submittedAt) {
    }

    record Decision(long campaignId, long operatorUserId, String decision, String comment, Instant decidedAt) {
    }

    List<PendingReview> findPending(Long beforeId, int limit);

    Optional<Decision> findLatestDecision(long campaignId, long ownerUserId);

    void recordDecision(long campaignId, long operatorUserId, String decision, String comment);
}
