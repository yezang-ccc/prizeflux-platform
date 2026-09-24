package com.prizeflux.campaign.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 草稿活动奖品配置请求：每日参与上限与奖品清单。
 * 活动归属由路径变量决定，奖品项不携带 {@code campaignId}，避免客户端声明归属。
 */
public record ConfigureCampaignRequest(
        @Positive(message = "每日参与上限必须为正数")
        int dailyLimit,
        @NotEmpty(message = "至少配置一个奖品")
        List<@Valid PrizeItem> prizes
) {}
