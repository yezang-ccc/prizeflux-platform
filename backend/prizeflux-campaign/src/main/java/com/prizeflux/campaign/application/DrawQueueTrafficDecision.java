package com.prizeflux.campaign.application;

/** A queue-specific admission or status-query rate decision. */
public record DrawQueueTrafficDecision(boolean allowed, long retryAfterSeconds) {
    public DrawQueueTrafficDecision {
        if (retryAfterSeconds < 0 || (allowed && retryAfterSeconds != 0)) {
            throw new IllegalArgumentException("Invalid draw queue traffic decision");
        }
    }

    public static DrawQueueTrafficDecision allow() {
        return new DrawQueueTrafficDecision(true, 0);
    }
}
