package com.prizeflux.campaign.domain.draw;

import com.prizeflux.campaign.domain.model.Probability;
import com.prizeflux.campaign.domain.random.RandomSource;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DrawStrategyTest {
    @Test
    void strategiesShareSortedTicketBoundariesAndNoPrizeRemainder() {
        List<DrawCandidate> unsorted = List.of(candidate(20, "0.5"), candidate(10, "0.2"));
        DrawStrategy linear = new LinearWeightDrawStrategy(unsorted);
        DrawStrategy precomputed = new PrecomputedTicketDrawStrategy(unsorted);
        assertSameResult(linear, precomputed, "0", OptionalLong.of(10));
        assertSameResult(linear, precomputed, "0.199999", OptionalLong.of(10));
        assertSameResult(linear, precomputed, "0.2", OptionalLong.of(20));
        assertSameResult(linear, precomputed, "0.699999", OptionalLong.of(20));
        assertSameResult(linear, precomputed, "0.7", OptionalLong.empty());
        assertSameResult(linear, precomputed, "0.999999", OptionalLong.empty());
    }

    @Test
    void zeroProbabilityAndEmptyDistributionNeverWin() {
        for (DrawStrategy strategy : strategies(List.of(candidate(1, "0"), candidate(2, "1")))) {
            assertThat(strategy.select(fixed("0"))).isEqualTo(OptionalLong.of(2));
        }
        for (DrawStrategy strategy : strategies(List.of())) {
            assertThat(strategy.select(fixed("0"))).isEmpty();
        }
    }

    @Test
    void trailingZerosDoNotExceedSixDigitTicketPrecision() {
        for (DrawStrategy strategy : strategies(List.of(candidate(1, "0.1000000")))) {
            assertThat(strategy.select(fixed("0.099999"))).isEqualTo(OptionalLong.of(1));
            assertThat(strategy.select(fixed("0.1"))).isEmpty();
        }
    }

    @Test
    void invalidConfigurationIsRejectedByBothStrategies() {
        List<List<DrawCandidate>> invalid = List.of(
                List.of(candidate(1, "0.5"), candidate(1, "0.1")),
                List.of(candidate(1, "0.8"), candidate(2, "0.3")),
                List.of(candidate(1, "0.0000001")),
                Arrays.asList(candidate(1, "0.1"), null));
        for (List<DrawCandidate> candidates : invalid) {
            assertInvalid(() -> new LinearWeightDrawStrategy(candidates));
            assertInvalid(() -> new PrecomputedTicketDrawStrategy(candidates));
        }
        assertInvalid(() -> new LinearWeightDrawStrategy(null));
        assertInvalid(() -> new PrecomputedTicketDrawStrategy(null));
    }

    @Test
    void invalidRandomSourceCannotProduceAResult() {
        for (DrawStrategy strategy : strategies(List.of(candidate(1, "1")))) {
            assertThatThrownBy(() -> strategy.select(() -> null)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> strategy.select(fixed("-0.01")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> strategy.select(fixed("1")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static List<DrawStrategy> strategies(List<DrawCandidate> candidates) {
        return List.of(new LinearWeightDrawStrategy(candidates), new PrecomputedTicketDrawStrategy(candidates));
    }

    private static DrawCandidate candidate(long id, String probability) {
        return new DrawCandidate(id, Probability.of(new BigDecimal(probability)));
    }

    private static RandomSource fixed(String value) {
        return () -> new BigDecimal(value);
    }

    private static void assertSameResult(DrawStrategy first, DrawStrategy second,
            String randomValue, OptionalLong expected) {
        assertThat(first.select(fixed(randomValue))).isEqualTo(expected);
        assertThat(second.select(fixed(randomValue))).isEqualTo(expected);
    }

    private static void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
    }
}
