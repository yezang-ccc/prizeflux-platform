package com.prizeflux.campaign.domain.random;

import com.prizeflux.campaign.infrastructure.random.ThreadLocalRandomSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RandomSourceTest {
    @Test
    void fixedSourceReturnsValuesInOrderAndFailsWhenExhausted() {
        RandomSource source = new FixedRandomSource(BigDecimal.ZERO, new BigDecimal("0.999999"));
        assertThat(source.nextUnitInterval()).isEqualByComparingTo("0");
        assertThat(source.nextUnitInterval()).isEqualByComparingTo("0.999999");
        assertThatThrownBy(source::nextUnitInterval).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fixedSourceRejectsInvalidSequence() {
        assertThatThrownBy(() -> new FixedRandomSource()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedRandomSource((BigDecimal) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedRandomSource(new BigDecimal("-0.1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedRandomSource(BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productionSourceAlwaysReturnsHalfOpenUnitInterval() {
        RandomSource source = new ThreadLocalRandomSource();
        for (int index = 0; index < 100; index++) {
            BigDecimal value = source.nextUnitInterval();
            assertThat(value).isGreaterThanOrEqualTo(BigDecimal.ZERO).isLessThan(BigDecimal.ONE);
        }
    }
}
