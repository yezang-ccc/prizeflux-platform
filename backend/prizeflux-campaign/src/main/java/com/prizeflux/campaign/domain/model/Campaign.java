package com.prizeflux.campaign.domain.model;

import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;

import java.time.Instant;

/**
 * 合法活动配置及其状态快照：构造守住不变量，迁移返回新对象。
 * 无HTTP或数据库依赖；不宣称旧快照可以解决持久化并发状态冲突。
 */
public final class Campaign {
    private final long id;
    private final String name;
    private final Instant startsAt;
    private final Instant endsAt;
    private final CampaignStatus status;

    /** 新活动只能创建为草稿，不能通过公开构造器绕过发布规则。 */
    public Campaign(long id, String name, Instant startsAt, Instant endsAt) {
        this(id, name, startsAt, endsAt, CampaignStatus.DRAFT);
    }

    /** 内部受控构造：状态由合法迁移决定，配置仍统一检查。 */
    private Campaign(long id, String name, Instant startsAt, Instant endsAt, CampaignStatus status) {
        validateConfiguration(id, name, startsAt, endsAt);
        this.id = id;
        this.name = name;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.status = status;
    }

    /** 即使绕过HTTP直接构造，也必须满足活动配置规则。 */
    private static void validateConfiguration(long id, String name, Instant startsAt, Instant endsAt) {
        if (id <= 0 || name == null || name.isBlank() || name.length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        if (startsAt == null || endsAt == null || !startsAt.isBefore(endsAt)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    /** 读取活动业务身份，不把对象引用相等当作业务身份相等。 */
    public long id() {
        return id;
    }

    /** 读取原名称；不暗中trim输入。 */
    public String name() {
        return name;
    }

    /** 读取包含在活动窗口中的开始时刻；Instant本身不可变。 */
    public Instant startsAt() {
        return startsAt;
    }

    /** 读取不包含在活动窗口中的结束时刻。 */
    public Instant endsAt() {
        return endsAt;
    }

    /** 读取管理状态；PUBLISHED还需要时间判断才能参与。 */
    public CampaignStatus status() {
        return status;
    }

    /** 仅草稿可发布；返回已发布的新快照，不修改原草稿。 */
    public Campaign publish() {
        if (status != CampaignStatus.DRAFT) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        return new Campaign(id, name, startsAt, endsAt, CampaignStatus.PUBLISHED);
    }

    /** 草稿和已发布可关闭；关闭为终止状态，不允许重复关闭。 */
    public Campaign close() {
        if (status == CampaignStatus.CLOSED) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        return new Campaign(id, name, startsAt, endsAt, CampaignStatus.CLOSED);
    }

    /** 显式传入时刻，仅已发布且位于[start,end)时允许参与。 */
    public boolean isAvailableAt(Instant now) {
        // 先检查参数，不能因草稿/关闭的短路判断而漏掉null。
        if (now == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        return status == CampaignStatus.PUBLISHED
                && !now.isBefore(startsAt)
                && now.isBefore(endsAt);
    }
}
