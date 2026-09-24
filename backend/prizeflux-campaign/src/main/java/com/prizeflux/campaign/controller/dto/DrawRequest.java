package com.prizeflux.campaign.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 用户抽奖请求形状：只携带活动 ID 和幂等键。
 * 用户身份由服务端安全上下文决定，绝不在请求体中接收 {@code userId}。
 */
public record DrawRequest(
        @Positive(message = "活动ID必须为正数")
        long campaignId,
        @NotBlank(message = "请求键不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{8,64}", message = "请求键需为8-64位字母数字、下划线或连字符")
        String requestKey
) {}
