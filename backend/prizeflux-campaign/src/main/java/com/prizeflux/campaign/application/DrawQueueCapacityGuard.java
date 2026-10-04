package com.prizeflux.campaign.application;

/** Rejects new work when sampled MySQL queue or connection-pool pressure is above configured limits. */
public interface DrawQueueCapacityGuard {
    void check(DrawQueueRepository.PressureSnapshot pressure);
}
