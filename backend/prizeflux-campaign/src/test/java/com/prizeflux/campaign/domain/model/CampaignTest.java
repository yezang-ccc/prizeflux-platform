package com.prizeflux.campaign.domain.model;

import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 纯Java活动测试：不启动Spring，不依赖真实时间或网络。 */
class CampaignTest {
    private static final Instant START = Instant.parse("2026-09-20T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-21T00:00:00Z");

    /** 合法构造保存原字段，名称不自动trim，新活动固定为草稿。 */
    @Test
    void validConstructionStartsAsDraft() {
        Campaign campaign = new Campaign(1L, " 活动 ", START, END);
        assertThat(campaign.id()).isEqualTo(1L);
        assertThat(campaign.name()).isEqualTo(" 活动 ");
        assertThat(campaign.startsAt()).isEqualTo(START);
        assertThat(campaign.endsAt()).isEqualTo(END);
        assertThat(campaign.status()).isEqualTo(CampaignStatus.DRAFT);
    }

    /** 活动身份必须为正数。ValueSource为每个long值分别执行一次。 */
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void invalidConstructionIdIsRejected(long id) {
        assertInvalidArgument(() -> new Campaign(id, "活动", START, END));
    }

    /** 名称不能是null、空串或纯空白。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void invalidConstructionNameIsRejected(String name) {
        assertInvalidArgument(() -> new Campaign(1L, name, START, END));
    }

    /** 最大名称长度100包含边界，不接受101。 */
    @Test
    void constructionNameLengthBoundaryIsEnforced() {
        assertThat(new Campaign(1L, "奖".repeat(100), START, END).name()).hasSize(100);
        assertInvalidArgument(() -> new Campaign(1L, "奖".repeat(101), START, END));
    }

    /** 开始和结束都不能为空。 */
    @Test
    void constructionRejectsMissingTimes() {
        assertInvalidArgument(() -> new Campaign(1L, "活动", null, END));
        assertInvalidArgument(() -> new Campaign(1L, "活动", START, null));
    }

    /** 时间相等或倒置都不能创建活动。 */
    @Test
    void constructionRejectsEmptyOrReversedWindow() {
        assertInvalidArgument(() -> new Campaign(1L, "活动", START, START));
        assertInvalidArgument(() -> new Campaign(1L, "活动", END, START));
    }

    /** 发布生成新对象，草稿和业务字段保持不变。 */
    @Test
    void publishReturnsNewPublishedCampaign() {
        Campaign original = draft();
        Campaign published = original.publish();
        assertThat(published).isNotSameAs(original);
        assertThat(published.status()).isEqualTo(CampaignStatus.PUBLISHED);
        assertThat(original.status()).isEqualTo(CampaignStatus.DRAFT);
        assertSameConfiguration(original, published);
    }

    /** 草稿可直接关闭，原草稿不被修改。 */
    @Test
    void draftCanCloseWithoutPublication() {
        Campaign original = draft();
        Campaign closed = original.close();
        assertThat(closed).isNotSameAs(original);
        assertThat(closed.status()).isEqualTo(CampaignStatus.CLOSED);
        assertThat(original.status()).isEqualTo(CampaignStatus.DRAFT);
        assertSameConfiguration(original, closed);
    }

    /** 已发布活动可关闭，原已发布对象不变。 */
    @Test
    void publishedCanClose() {
        Campaign original = draft().publish();
        Campaign closed = original.close();
        assertThat(closed).isNotSameAs(original);
        assertThat(closed.status()).isEqualTo(CampaignStatus.CLOSED);
        assertThat(original.status()).isEqualTo(CampaignStatus.PUBLISHED);
        assertSameConfiguration(original, closed);
    }

    /** 非法迁移归为业务规则失败，不是字段非法。 */
    @ParameterizedTest
    @ValueSource(strings = {"publishPublished", "publishClosed", "closeClosed"})
    void illegalTransitionIsRejected(String action) {
        Campaign published = draft().publish();
        Campaign closed = published.close();
        Runnable operation = switch (action) {
            case "publishPublished" -> () -> published.publish();
            case "publishClosed" -> () -> closed.publish();
            case "closeClosed" -> () -> closed.close();
            default -> throw new AssertionError("未知测试动作");
        };
        assertErrorCode(operation, ErrorCode.BUSINESS_RULE_VIOLATION);
    }

    /** 已发布活动按[start,end)判断可参与，发布本身不依赖当前系统时间。 */
    @ParameterizedTest
    @MethodSource("windowCases")
    void publishedAvailabilityUsesHalfOpenWindow(Instant now, boolean expected) {
        assertThat(draft().publish().isAvailableAt(now)).isEqualTo(expected);
    }

    /** 在时间窗口内，草稿与关闭状态也不允许参与。 */
    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"DRAFT", "CLOSED"})
    void unpublishedOrClosedCannotParticipate(CampaignStatus status) {
        assertThat(inState(status).isAvailableAt(START)).isFalse();
    }

    /** 任意状态都先拒绝null时间，避免短路判断跳过参数校验。 */
    @ParameterizedTest
    @EnumSource(CampaignStatus.class)
    void nullNowIsRejectedInEveryState(CampaignStatus status) {
        Campaign campaign = inState(status);
        assertInvalidArgument(() -> campaign.isAvailableAt(null));
    }

    /** 为MethodSource提供固定时间和期望值；每个Arguments是一组测试参数。 */
    private static Stream<Arguments> windowCases() {
        return Stream.of(
                Arguments.of(START.minusNanos(1), false),
                Arguments.of(START, true),
                Arguments.of(END.minusNanos(1), true),
                Arguments.of(END, false));
    }

    /** 统一生成合法草稿；不是框架方法，只是本测试的辅助方法。 */
    private static Campaign draft() {
        return new Campaign(1L, "活动", START, END);
    }

    /** 通过公开合法迁移准备状态，不绕过生产类私有构造器。 */
    private static Campaign inState(CampaignStatus status) {
        Campaign campaign = draft();
        return switch (status) {
            case DRAFT -> campaign;
            case PENDING_REVIEW -> campaign.submitForReview();
            case PUBLISHED -> campaign.publish();
            case REJECTED -> campaign.submitForReview().rejectReview();
            case CLOSED -> campaign.close();
        };
    }

    /** 比较迁移前后配置，不使用对象equals来定义业务身份。 */
    private static void assertSameConfiguration(Campaign before, Campaign after) {
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.name()).isEqualTo(before.name());
        assertThat(after.startsAt()).isEqualTo(before.startsAt());
        assertThat(after.endsAt()).isEqualTo(before.endsAt());
    }

    /** Runnable接收一个待执行动作；统一检查非法字段的业务错误枚举。 */
    private static void assertInvalidArgument(Runnable operation) {
        assertErrorCode(operation, ErrorCode.INVALID_ARGUMENT);
    }

    /** AssertJ执行动作并检查异常类型和枚举，不检查HTTP响应。 */
    private static void assertErrorCode(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(
                BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
    }
}
