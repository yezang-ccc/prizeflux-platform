package com.prizeflux.worker.application;

/** One database-fenced execution claim. Attempt count includes the claimed execution. */
public record FulfillmentClaim(long id, long drawId, String eventId, String payload,
        int attemptCount, String leaseToken) {
}
