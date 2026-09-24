package com.prizeflux.campaign.domain.draw;

import com.prizeflux.campaign.domain.random.RandomSource;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

/** 用约 4MB 的票号索引换取单次 O(1) 选择；由调用方缓存冻结配置实例。 */
public final class PrecomputedTicketDrawStrategy implements DrawStrategy {
    private static final int NO_PRIZE = -1;

    private final DrawDistribution distribution;
    private final int[] candidateIndexByTicket;

    public PrecomputedTicketDrawStrategy(List<DrawCandidate> candidates) {
        distribution = new DrawDistribution(candidates);
        candidateIndexByTicket = new int[DrawDistribution.TICKET_COUNT];
        Arrays.fill(candidateIndexByTicket, NO_PRIZE);
        int firstTicket = 0;
        for (int index = 0; index < distribution.size(); index++) {
            int nextTicket = firstTicket + distribution.weightAt(index);
            Arrays.fill(candidateIndexByTicket, firstTicket, nextTicket, index);
            firstTicket = nextTicket;
        }
    }

    @Override
    public OptionalLong select(RandomSource randomSource) {
        int index = candidateIndexByTicket[DrawDistribution.ticketFrom(randomSource)];
        return index == NO_PRIZE ? OptionalLong.empty()
                : OptionalLong.of(distribution.prizeIdAt(index));
    }
}
