package com.prizeflux.campaign.domain.draw;

import com.prizeflux.campaign.domain.random.RandomSource;

import java.util.List;
import java.util.OptionalLong;

/** 构建一次冻结分布，每次选择按奖品 ID 顺序扫描权重。 */
public final class LinearWeightDrawStrategy implements DrawStrategy {
    private final DrawDistribution distribution;

    public LinearWeightDrawStrategy(List<DrawCandidate> candidates) {
        distribution = new DrawDistribution(candidates);
    }

    @Override
    public OptionalLong select(RandomSource randomSource) {
        int ticket = DrawDistribution.ticketFrom(randomSource);
        int upperExclusive = 0;
        for (int index = 0; index < distribution.size(); index++) {
            upperExclusive += distribution.weightAt(index);
            if (ticket < upperExclusive) {
                return OptionalLong.of(distribution.prizeIdAt(index));
            }
        }
        return OptionalLong.empty();
    }
}
