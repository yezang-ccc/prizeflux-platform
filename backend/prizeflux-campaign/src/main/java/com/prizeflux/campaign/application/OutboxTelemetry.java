package com.prizeflux.campaign.application;

/** Bounded Relay outcome vocabulary; event identifiers belong in logs, never metric dimensions. */
public interface OutboxTelemetry {
    enum PublishOutcome { SENT, RETRY_SCHEDULED, DEAD_LETTERED, CLAIM_LOST }
    enum DatabaseStage { RECLAIM, CLAIM, MARK_SENT, RELEASE_RETRY, MARK_DEAD }
    enum StageOutcome { SUCCESS, FAILURE }

    void recordPublish(PublishOutcome outcome, long elapsedNanos, long createToConfirmNanos);

    void recordLeaseReclaimed(long eventCount);

    /** Times repository calls, not individual SQL or lock waits. Success may return no rows. */
    default void recordDatabaseStage(DatabaseStage stage, StageOutcome outcome, long elapsedNanos) { }
}
