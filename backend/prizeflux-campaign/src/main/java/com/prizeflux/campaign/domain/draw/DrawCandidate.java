package com.prizeflux.campaign.domain.draw;

import com.prizeflux.campaign.domain.model.Probability;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;

/** 冻结分布中的一项；实时库存不属于概率候选。 */
public record DrawCandidate(long prizeId, Probability probability) {
    public DrawCandidate {
        if (prizeId <= 0 || probability == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
