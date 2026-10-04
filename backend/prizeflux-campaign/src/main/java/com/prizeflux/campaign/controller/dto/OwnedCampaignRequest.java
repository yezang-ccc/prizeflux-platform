package com.prizeflux.campaign.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** USER 活动基础资料；ID、owner 与状态均由服务端掌握。 */
public record OwnedCampaignRequest(
        @NotBlank(message = "活动名称不能为空") @Size(max = 100, message = "活动名称不能超过100字符") String name,
        @NotNull(message = "活动开始时间不能为空") Instant startsAt,
        @NotNull(message = "活动结束时间不能为空") Instant endsAt) {
}
