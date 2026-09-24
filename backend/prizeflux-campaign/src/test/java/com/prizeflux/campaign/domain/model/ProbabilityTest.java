package com.prizeflux.campaign.domain.model;

import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProbabilityTest {
    @Test
    void acceptsBoundsAndNumericEquality() {
        assertThat(Probability.of(BigDecimal.ZERO).value()).isEqualByComparingTo("0");
        assertThat(Probability.of(BigDecimal.ONE).value()).isEqualByComparingTo("1");
        Probability first = Probability.of(new BigDecimal("1.0"));
        Probability second = Probability.of(new BigDecimal("1.00"));
        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void rejectsMissingOrOutOfRangeValue() {
        assertInvalid(() -> Probability.of(null));
        assertInvalid(() -> Probability.of(new BigDecimal("-0.0001")));
        assertInvalid(() -> Probability.of(new BigDecimal("1.0001")));
    }

    @Test
    void sumsExactlyAndAllowsUnallocatedChance() {
        assertThat(Probability.sum(List.of())).isEqualByComparingTo("0");
        assertThat(Probability.sum(List.of(
                Probability.of(new BigDecimal("0.1")),
                Probability.of(new BigDecimal("0.2"))))).isEqualByComparingTo("0.3");
        assertThat(Probability.sum(List.of(
                Probability.of(new BigDecimal("0.1")),
                Probability.of(new BigDecimal("0.9"))))).isEqualByComparingTo("1");
    }

    @Test
    void rejectsNullEntryAndSumAboveOne() {
        assertInvalid(() -> Probability.sum(null));
        assertInvalid(() -> Probability.sum(Arrays.asList(Probability.of(BigDecimal.ZERO), null)));
        assertInvalid(() -> Probability.sum(List.of(
                Probability.of(new BigDecimal("0.8")),
                Probability.of(new BigDecimal("0.3")))));
    }

    private static void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
    }
}
