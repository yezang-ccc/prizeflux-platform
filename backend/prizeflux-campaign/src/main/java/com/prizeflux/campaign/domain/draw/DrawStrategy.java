package com.prizeflux.campaign.domain.draw;

import com.prizeflux.campaign.domain.random.RandomSource;

import java.util.OptionalLong;

/** 只做候选选择；库存不足和业务资格由事务用例处理。 */
public interface DrawStrategy {
    OptionalLong select(RandomSource randomSource);
}
