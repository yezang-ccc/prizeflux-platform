package com.prizeflux.campaign.domain.model;

import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;

import java.math.BigDecimal;

/**
 * 合法奖品配置：数量是总数量配置，概率是单奖品概率。
 * 不验证活动在数据库中存在、全活动概率总和，也不执行实时扣库存。
 */
public final class Prize {
    private final long id;
    private final long campaignId;
    private final String name;
    private final int totalStock;
    private final BigDecimal probability;

    /** 先校验再保存字段，HTTP之外直接new也不能产生非法配置。 */
    public Prize(long id, long campaignId, String name, int totalStock, BigDecimal probability) {
        validateConfiguration(id, campaignId, name, totalStock, probability);
        this.id = id;
        this.campaignId = campaignId;
        this.name = name;
        this.totalStock = totalStock;
        this.probability = probability;
    }

    /** 单字段合法性检查：概率按数值比较，不把小数位数当成范围限制。 */
    private static void validateConfiguration(long id, long campaignId, String name,
            int totalStock, BigDecimal probability) {
        if (id <= 0 || campaignId <= 0 || name == null || name.isBlank() || name.length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        if (totalStock < 0 || probability == null
                || probability.compareTo(BigDecimal.ZERO) < 0
                || probability.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    /** 读取奖品业务身份。 */
    public long id() {
        return id;
    }

    /** 读取活动归属标识，不代表已验证活动存在。 */
    public long campaignId() {
        return campaignId;
    }

    /** 读取原奖品名称，不自动trim。 */
    public String name() {
        return name;
    }

    /** 读取总数量配置，不是并发扣减后的实时剩余数量。 */
    public int totalStock() {
        return totalStock;
    }

    /** 读取不可变的BigDecimal概率；范围合法不保证全活动总和合法。 */
    public BigDecimal probability() {
        return probability;
    }
}
