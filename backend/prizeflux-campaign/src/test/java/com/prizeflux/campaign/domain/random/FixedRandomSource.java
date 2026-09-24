package com.prizeflux.campaign.domain.random;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** 仅测试使用；每个固定值最多读取一次。 */
final class FixedRandomSource implements RandomSource {
    private final List<BigDecimal> values;
    private int nextIndex;

    FixedRandomSource(BigDecimal... values) {
        if (values == null || values.length == 0 || Arrays.stream(values).anyMatch(
                value -> value == null || value.compareTo(BigDecimal.ZERO) < 0
                        || value.compareTo(BigDecimal.ONE) >= 0)) {
            throw new IllegalArgumentException("Random values must be in [0,1)");
        }
        this.values = List.copyOf(Arrays.asList(values));
    }

    @Override
    public BigDecimal nextUnitInterval() {
        if (nextIndex >= values.size()) {
            throw new IllegalStateException("Fixed random sequence exhausted");
        }
        return values.get(nextIndex++);
    }
}
