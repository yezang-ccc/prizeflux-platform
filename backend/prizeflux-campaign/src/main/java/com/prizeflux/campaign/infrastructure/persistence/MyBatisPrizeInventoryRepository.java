package com.prizeflux.campaign.infrastructure.persistence;

import com.prizeflux.campaign.domain.repository.PrizeInventoryRepository;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** 只把 SQL 受影响行数解释为是否扣减成功，不在 Java 中读后写。 */
@Repository
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class MyBatisPrizeInventoryRepository implements PrizeInventoryRepository {
    private final PrizeInventoryMapper mapper;

    public MyBatisPrizeInventoryRepository(PrizeInventoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean tryDecrement(long campaignId, int configVersion, long prizeId) {
        validateKey(campaignId, configVersion, prizeId);
        int updated = mapper.decrementIfAvailable(campaignId, configVersion, prizeId);
        if (updated > 1) {
            throw new IllegalStateException("Inventory update affected more than one row");
        }
        return updated == 1;
    }

    @Override
    public int remaining(long campaignId, int configVersion, long prizeId) {
        validateKey(campaignId, configVersion, prizeId);
        Integer remaining = mapper.findRemaining(campaignId, configVersion, prizeId);
        if (remaining == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return remaining;
    }

    private static void validateKey(long campaignId, int configVersion, long prizeId) {
        if (campaignId <= 0 || configVersion <= 0 || prizeId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
