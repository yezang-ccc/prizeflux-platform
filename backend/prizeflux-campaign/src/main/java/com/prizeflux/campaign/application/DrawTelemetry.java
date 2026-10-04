package com.prizeflux.campaign.application;

/** Bounded telemetry vocabulary for the authoritative draw transaction. */
public interface DrawTelemetry {
    enum TransactionOutcome { COMMITTED, ROLLED_BACK }

    enum DatabaseSegment {
        CONFIG_LOCK_AND_LOAD,
        IDEMPOTENCY_INSERT,
        QUOTA_UPDATE,
        INVENTORY_DECREMENT,
        RESULT_INSERT,
        OUTBOX_INSERT,
        REQUEST_COMPLETE
    }

    enum SegmentOutcome { SUCCESS, FAILURE }

    void recordTransaction(TransactionOutcome outcome, long elapsedNanos);

    void recordDatabaseSegment(DatabaseSegment segment, SegmentOutcome outcome, long elapsedNanos);
}
