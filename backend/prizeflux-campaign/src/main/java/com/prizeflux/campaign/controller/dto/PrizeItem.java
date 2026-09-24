package com.prizeflux.campaign.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 配置请求中的单条奖品项；{@code [0,1]} 概率范围与重复 ID 由领域值对象与配置用例统一校验，
 * 这里只做基础形状约束。
 */
public record PrizeItem(
        @Positive(message = "奖品ID必须为正数")
        long id,
        @NotBlank(message = "奖品名称不能为空")
        @Size(max = 100, message = "奖品名称不能超过100字符")
        String name,
        @PositiveOrZero(message = "库存不能为负数")
        int totalStock,
        @NotNull(message = "概率不能为空")
        BigDecimal probability
) {}
