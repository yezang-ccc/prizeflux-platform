package com.prizeflux.campaign.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 活动创建入口的请求形状，不允许客户端指定初始状态。
 * Spring MVC 先把 JSON 转换成该对象，再在 @Valid 参数处执行这些约束。
 * 仅 new 此 record 不会自动执行 Bean Validation；约束由校验器执行。
 */
public record CreateCampaignRequest(
        @Positive(message = "活动ID必须为正数")
        long id,
        @NotBlank(message = "活动名称不能为空")
        @Size(max = 100, message = "活动名称不能超过100字符")
        String name,
        @NotNull(message = "活动开始时间不能为空")
        Instant startsAt,
        @NotNull(message = "活动结束时间不能为空")
        Instant endsAt
) {}
