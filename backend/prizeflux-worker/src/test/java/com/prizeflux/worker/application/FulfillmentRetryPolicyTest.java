package com.prizeflux.worker.application;

import com.prizeflux.contract.event.FulfillmentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FulfillmentRetryPolicyTest {
    private final FulfillmentRetryPolicy policy = new FulfillmentRetryPolicy();
    private final Instant now = Instant.parse("2026-09-24T00:00:00Z");

    @Test
    void transientFailuresUseBoundedExponentialBackoff() {
        assertEquals(new FulfillmentRetryPolicy.RetryDecision(FulfillmentStatus.RETRYING, now.plusSeconds(1)),
                policy.afterFailure(1, false, now));
        assertEquals(new FulfillmentRetryPolicy.RetryDecision(FulfillmentStatus.RETRYING, now.plusSeconds(2)),
                policy.afterFailure(2, false, now));
        assertEquals(new FulfillmentRetryPolicy.RetryDecision(FulfillmentStatus.MANUAL_REVIEW, null),
                policy.afterFailure(3, false, now));
    }

    @Test
    void permanentFailureImmediatelyNeedsReview() {
        var decision = policy.afterFailure(1, true, now);
        assertEquals(FulfillmentStatus.MANUAL_REVIEW, decision.status());
        assertNull(decision.nextAttemptAt());
    }

    @Test
    void invalidAttemptOrTimeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> policy.afterFailure(0, false, now));
        assertThrows(IllegalArgumentException.class, () -> policy.afterFailure(1, false, null));
    }
}
