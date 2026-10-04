package com.prizeflux.worker.application;

/** Bounded operational outcomes; event, draw, and user identifiers never become metric labels. */
public interface FulfillmentTelemetry {
    enum ConsumeOutcome { PROCESSED, DUPLICATE, MALFORMED_PAYLOAD, INVALID_EVENT_KEY, PROCESSING_FAILED }
    enum ExecutionOutcome { SUCCEEDED, RETRY_SCHEDULED, MANUAL_REVIEW, CLAIM_LOST }

    void recordConsume(ConsumeOutcome outcome, long elapsedNanos);

    void recordExecution(ExecutionOutcome outcome, long elapsedNanos);
}
