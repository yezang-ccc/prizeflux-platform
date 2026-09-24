package com.prizeflux.campaign.domain.draw;

import com.prizeflux.campaign.domain.random.RandomSource;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 两种策略共用的百万票号分布，发布配置不允许无法精确落在票号上的概率。 */
final class DrawDistribution {
    static final int TICKET_COUNT = 1_000_000;
    private static final BigDecimal UNIT = BigDecimal.ONE;

    private final long[] prizeIds;
    private final int[] weights;

    DrawDistribution(List<DrawCandidate> candidates) {
        if (candidates == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        List<DrawCandidate> sorted = new ArrayList<>(candidates.size());
        for (DrawCandidate candidate : candidates) {
            if (candidate == null) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            }
            sorted.add(candidate);
        }
        sorted.sort(Comparator.comparingLong(DrawCandidate::prizeId));

        prizeIds = new long[sorted.size()];
        weights = new int[sorted.size()];
        Set<Long> seen = new HashSet<>();
        int total = 0;
        for (int index = 0; index < sorted.size(); index++) {
            DrawCandidate candidate = sorted.get(index);
            if (!seen.add(candidate.prizeId())) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            }
            BigDecimal probability = candidate.probability().value();
            // 尾随零不增加有效精度，例如 0.1000000 与 0.1 应使用相同票数。
            if (probability.stripTrailingZeros().scale() > 6) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            }
            int weight = probability.movePointRight(6).intValueExact();
            total += weight;
            if (total > TICKET_COUNT) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            }
            prizeIds[index] = candidate.prizeId();
            weights[index] = weight;
        }
    }

    int size() {
        return weights.length;
    }

    int weightAt(int index) {
        return weights[index];
    }

    long prizeIdAt(int index) {
        return prizeIds[index];
    }

    static int ticketFrom(RandomSource randomSource) {
        if (randomSource == null) {
            throw new IllegalArgumentException("Random source is required");
        }
        BigDecimal value = randomSource.nextUnitInterval();
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(UNIT) >= 0) {
            throw new IllegalStateException("Random source returned a value outside [0,1)");
        }
        return value.movePointRight(6).setScale(0, RoundingMode.DOWN).intValueExact();
    }
}
