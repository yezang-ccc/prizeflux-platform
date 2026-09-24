package com.prizeflux.campaign.domain.model;

import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;

import java.math.BigDecimal;
import java.util.Collection;

/** 单项概率的精确十进制值；集合求和允许留下未中奖区间。 */
public final class Probability {
    private final BigDecimal value;

    private Probability(BigDecimal value) {
        this.value = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    public static Probability of(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0
                || value.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        return new Probability(value);
    }

    public static BigDecimal sum(Collection<Probability> probabilities) {
        if (probabilities == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Probability probability : probabilities) {
            if (probability == null) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            }
            total = total.add(probability.value);
            if (total.compareTo(BigDecimal.ONE) > 0) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        return total;
    }

    public BigDecimal value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Probability probability
                && value.compareTo(probability.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
