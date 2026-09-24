package com.prizeflux.campaign.domain.draw;

import com.prizeflux.campaign.domain.model.Probability;
import com.prizeflux.campaign.domain.random.RandomSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** 固定伪随机样本只检验实现是否明显偏离规格，不宣称证明绝对公平。 */
class DrawDistributionTest {
    private static final int SAMPLE_COUNT = 200_000;
    private static final long SEED = 20260923L;
    private static final int TICKETS = 1_000_000;
    private static final double[] EXPECTED = {0.10, 0.20, 0.30, 0.40};

    @Test
    void bothStrategiesMatchDeclaredDistributionOnReproducibleSample() {
        List<DrawCandidate> candidates = List.of(
                candidate(30, "0.30"), candidate(10, "0.10"), candidate(20, "0.20"));
        int[] linear = sample(new LinearWeightDrawStrategy(candidates));
        int[] precomputed = sample(new PrecomputedTicketDrawStrategy(candidates));

        // 两策略以同一百万票号空间解释相同随机票序列，因此不仅频率相近，结果应逐票相同。
        assertThat(precomputed).containsExactly(linear);
        double chiSquare = 0;
        for (int index = 0; index < EXPECTED.length; index++) {
            double expectedCount = SAMPLE_COUNT * EXPECTED[index];
            double difference = linear[index] - expectedCount;
            chiSquare += difference * difference / expectedCount;
        }
        // 四类互斥结果有三个自由度；20 是宽松的异常侦测阈值，而非公平性证明。
        assertThat(chiSquare).isLessThan(20.0);
        assertThat(linear).satisfies(counts ->
                assertThat(counts[0] + counts[1] + counts[2] + counts[3]).isEqualTo(SAMPLE_COUNT));
        System.out.printf("draw-distribution seed=%d samples=%d prize10=%d prize20=%d prize30=%d noPrize=%d chiSquare=%.4f%n",
                SEED, SAMPLE_COUNT, linear[0], linear[1], linear[2], linear[3], chiSquare);
    }

    private static int[] sample(DrawStrategy strategy) {
        Random random = new Random(SEED);
        RandomSource source = () -> BigDecimal.valueOf(random.nextInt(TICKETS), 6);
        int[] counts = new int[4];
        for (int draw = 0; draw < SAMPLE_COUNT; draw++) {
            OptionalLong selected = strategy.select(source);
            if (selected.isEmpty()) {
                counts[3]++;
            } else {
                long prizeId = selected.getAsLong();
                if (prizeId == 10) {
                    counts[0]++;
                } else if (prizeId == 20) {
                    counts[1]++;
                } else if (prizeId == 30) {
                    counts[2]++;
                } else {
                    throw new AssertionError("Unexpected prize ID: " + prizeId);
                }
            }
        }
        return counts;
    }

    private static DrawCandidate candidate(long id, String probability) {
        return new DrawCandidate(id, Probability.of(new BigDecimal(probability)));
    }
}
