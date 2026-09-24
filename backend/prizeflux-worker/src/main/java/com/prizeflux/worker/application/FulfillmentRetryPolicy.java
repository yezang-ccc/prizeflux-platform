package com.prizeflux.worker.application;

import com.prizeflux.contract.event.FulfillmentStatus;

import java.time.Duration;
import java.time.Instant;

/** Pure retry policy; attempt is the number of executions that have already failed. */
public final class FulfillmentRetryPolicy {
    public static final int MAX_ATTEMPTS = 3;
    private static final Duration BASE_DELAY = Duration.ofSeconds(1);

    public RetryDecision afterFailure(int failedAttempts, boolean permanent, Instant now) {
        if (failedAttempts < 1 || now == null) {
            throw new IllegalArgumentException("A failed attempt and timestamp are required");
        }
        if (permanent || failedAttempts >= MAX_ATTEMPTS) {
            return new RetryDecision(FulfillmentStatus.MANUAL_REVIEW, null);
        }
        long seconds = BASE_DELAY.multipliedBy(1L << (failedAttempts - 1)).toSeconds();
        return new RetryDecision(FulfillmentStatus.RETRYING, now.plusSeconds(seconds));
    }

    public int maxAttempts() { return MAX_ATTEMPTS; }

    public record RetryDecision(FulfillmentStatus status, Instant nextAttemptAt) {
    }
}
