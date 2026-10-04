package com.prizeflux.campaign.application;

/** Admission and status-query protection; its implementation is outside the business transaction. */
public interface DrawQueueTrafficGuard {
    DrawQueueTrafficDecision checkSubmission(long userId, long campaignId);
    DrawQueueTrafficDecision checkStatusQuery(long userId);
}
