package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.draw.DrawCandidate;
import com.prizeflux.contract.event.PrizeFulfillmentEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 抽奖事务所需的数据库操作端口；调用方负责事务边界。 */
public interface DrawRepository {
    record ConfigSnapshot(int version, int dailyLimit, String campaignStatus,
                          Instant startsAt, Instant endsAt, List<DrawCandidate> candidates) {
    }

    record StoredDraw(byte[] fingerprint, boolean completed, DrawResult result) {
        public StoredDraw {
            fingerprint = fingerprint.clone();
        }

        @Override
        public byte[] fingerprint() {
            return fingerprint.clone();
        }
    }

    Optional<StoredDraw> findByKey(long userId, String requestKey);

    /** 按用户返回其抽奖结果，按 id 降序且 id < beforeId，最多 limit 条（稳定游标分页）。 */
    List<DrawHistoryEntry> findByUser(long userId, long beforeId, int limit);

    /** 返回属于该用户的单条抽奖结果，用于 {@code GET /api/me/draws/{id}}；不存在或非本人返回空。 */
    Optional<DrawHistoryEntry> findById(long userId, long id);

    Optional<ConfigSnapshot> lockPublishedConfig(long campaignId);

    long insertRequest(long userId, long campaignId, int configVersion,
                       String requestKey, byte[] fingerprint);

    boolean tryConsumeQuota(long campaignId, long userId, LocalDate businessDay, int limit);

    DrawResult insertResult(long requestId, long campaignId, int configVersion,
                            long userId, DrawOutcome outcome, Long prizeId);

    void completeRequest(long requestId);

    void insertOutbox(PrizeFulfillmentEvent event);
}
