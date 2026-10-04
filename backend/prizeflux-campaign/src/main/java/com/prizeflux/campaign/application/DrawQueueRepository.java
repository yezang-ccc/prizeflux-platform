package com.prizeflux.campaign.application;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** 持久化异步抽奖受理状态及其 Outbox 通知。 */
public interface DrawQueueRepository {
    enum Status { QUEUED, PROCESSING, RETRY_WAIT, COMPLETED, REJECTED, MANUAL_REVIEW }

    record PressureSnapshot(long pendingCount, long oldestAgeMillis) {
        public PressureSnapshot {
            if (pendingCount < 0 || oldestAgeMillis < 0) {
                throw new IllegalArgumentException("Invalid queue pressure snapshot");
            }
        }
    }

    record Entry(long id, long userId, long campaignId, String requestKey,
                 byte[] fingerprint, Status status, DrawResult result, String errorCode,
                 int attempts, String lastErrorCode, String claimToken,
                 Instant leaseExpiresAt, Instant nextAttemptAt,
                 Instant createdAt, Instant completedAt) {
        public Entry {
            fingerprint = fingerprint.clone();
        }

        @Override
        public byte[] fingerprint() {
            return fingerprint.clone();
        }
    }

    Optional<Entry> findByUserAndKey(long userId, String requestKey);
    Optional<Entry> findById(long id);
    PressureSnapshot pressureSnapshot();
    List<Long> findDueIds(int limit);
    long enqueue(long userId, long campaignId, String requestKey, byte[] fingerprint);
    boolean claim(long id, String claimToken, Duration leaseDuration);
    boolean recordAttempt(long id, String claimToken, int maxAttempts);
    boolean heartbeat(long id, String claimToken, Duration leaseDuration);
    boolean complete(long id, String claimToken, long drawResultId);
    boolean reject(long id, String claimToken, String errorCode);
    boolean retry(long id, String claimToken, String errorCode, Duration delay);
    boolean manualReview(long id, String claimToken, String errorCode);
}
