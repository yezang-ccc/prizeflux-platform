package com.prizeflux.campaign.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 创建前的只读草案校验输入，不含任何可写身份或资源字段。 */
public record ValidateOwnedCampaignRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @Positive int dailyLimit,
        @NotEmpty @Size(max = 50) List<@Valid PrizeItem> prizes) {
}
