package com.prizeflux.campaign.application;

/** Bounded outcome vocabulary for queue telemetry; request/user IDs are intentionally not dimensions. */
public interface DrawQueueTelemetry {
    enum SubmissionOutcome {
        ACCEPTED,
        REPLAYED,
        RATE_LIMITED,
        CAPACITY_REJECTED,
        PROTECTION_UNAVAILABLE,
        FAILED
    }

    enum QueryOutcome { FOUND, NOT_FOUND, RATE_LIMITED, FAILED }

    enum ProcessingOutcome {
        COMPLETED,
        REJECTED,
        RETRY_SCHEDULED,
        MANUAL_REVIEW,
        ALREADY_TERMINAL,
        CLAIMED_ELSEWHERE,
        CLAIM_LOST,
        FAILED
    }

    void recordSubmission(SubmissionOutcome outcome, long elapsedNanos);

    void recordStatusQuery(QueryOutcome outcome, long elapsedNanos);

    void recordProcessing(ProcessingOutcome outcome, long elapsedNanos);
}
