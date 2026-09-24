package com.prizeflux.campaign.infrastructure.random;

import com.prizeflux.campaign.domain.random.RandomSource;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

/** 生产环境的普通伪随机源，不承诺密码学安全。 */
public final class ThreadLocalRandomSource implements RandomSource {
    @Override
    public BigDecimal nextUnitInterval() {
        return BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble());
    }
}
