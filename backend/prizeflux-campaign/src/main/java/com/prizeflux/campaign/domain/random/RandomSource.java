package com.prizeflux.campaign.domain.random;

import java.math.BigDecimal;

/** 抽奖策略从此端口取得半开区间 [0,1) 的随机值。 */
public interface RandomSource {
    BigDecimal nextUnitInterval();
}
