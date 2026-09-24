package com.prizeflux.campaign.domain.repository;

/** MySQL 库存是最终扣减事实；Redis 预占仅作可恢复的入口优化。 */
public interface PrizeInventoryRepository {
    boolean tryDecrement(long campaignId, int configVersion, long prizeId);

    int remaining(long campaignId, int configVersion, long prizeId);
}
