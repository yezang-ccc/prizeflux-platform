package com.prizeflux.campaign.domain.model;

import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 奖品配置单元测试：不验证数据库活动存在、概率总和或实时库存扣减。 */
class PrizeTest {
    /** 合法配置保存全部传入字段，不自动trim或改变概率精度。 */
    @Test
    void validConfigurationPreservesFields() {
        BigDecimal probability = new BigDecimal("0.2500");
        Prize prize = new Prize(2L, 1L, " 奖品 ", 10, probability);
        assertThat(prize.id()).isEqualTo(2L);
        assertThat(prize.campaignId()).isEqualTo(1L);
        assertThat(prize.name()).isEqualTo(" 奖品 ");
        assertThat(prize.totalStock()).isEqualTo(10);
        assertThat(prize.probability()).isEqualTo(probability);
    }

    /** 奖品id必须为正数。 */
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void nonPositivePrizeIdIsRejected(long id) {
        assertInvalidArgument(() -> new Prize(id, 1L, "奖品", 10, BigDecimal.ONE));
    }

    /** 归属id必须为正数，但本轮不查询对应活动是否存在。 */
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void nonPositiveCampaignIdIsRejected(long campaignId) {
        assertInvalidArgument(() -> new Prize(2L, campaignId, "奖品", 10, BigDecimal.ONE));
    }

    /** null、空串和纯空白名称均拒绝。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void invalidNameIsRejected(String name) {
        assertInvalidArgument(() -> new Prize(2L, 1L, name, 10, BigDecimal.ONE));
    }

    /** 长度边界：100通过，101拒绝。 */
    @Test
    void nameLengthBoundaryIsEnforced() {
        assertThat(new Prize(2L, 1L, "奖".repeat(100), 10, BigDecimal.ONE).name()).hasSize(100);
        assertInvalidArgument(() -> new Prize(2L, 1L, "奖".repeat(101), 10, BigDecimal.ONE));
    }

    /** 总数量配置不能为负。 */
    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void negativeStockIsRejected(int stock) {
        assertInvalidArgument(() -> prize(stock, BigDecimal.ONE));
    }

    /** 零和正数配置合法；读取配置不是实时扣库存。 */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, Integer.MAX_VALUE})
    void nonNegativeStockIsAccepted(int stock) {
        assertThat(prize(stock, BigDecimal.ONE).totalStock()).isEqualTo(stock);
    }

    /** NullSource提供null；其他文本转成BigDecimal后交给领域类校验范围。 */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"-0.01", "-1", "1.01", "2"})
    void missingOrOutOfRangeProbabilityIsRejected(String text) {
        BigDecimal probability = text == null ? null : new BigDecimal(text);
        assertInvalidArgument(() -> prize(10, probability));
    }

    /** 概率包含0和1边界；1.0、1.00按数值都合法。 */
    @ParameterizedTest
    @ValueSource(strings = {"0", "0.25", "1", "1.0", "1.00"})
    void validProbabilityIsAccepted(String text) {
        BigDecimal probability = new BigDecimal(text);
        assertThat(prize(10, probability).probability()).isEqualTo(probability);
    }

    /** 只准备一个固定身份/名称的奖品，让用例聚焦数量和概率。 */
    private static Prize prize(int stock, BigDecimal probability) {
        return new Prize(2L, 1L, "奖品", stock, probability);
    }

    /** 本地辅助方法：执行动作，用AssertJ检查异常及既有错误枚举。 */
    private static void assertInvalidArgument(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(
                BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
    }
}
