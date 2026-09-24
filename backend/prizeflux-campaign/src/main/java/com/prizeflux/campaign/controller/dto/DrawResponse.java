package com.prizeflux.campaign.controller.dto;

import com.prizeflux.campaign.application.DrawOutcome;
import com.prizeflux.campaign.application.DrawResult;

/**
 * 对外只读抽奖结果投影。不暴露内部发奖状态 {@code fulfillmentStatus}；
 * 用户身份不在响应里重复（可经 {@code /api/auth/me} 得知）。
 */
public record DrawResponse(
        long requestId,
        long campaignId,
        DrawOutcome outcome,
        Long prizeId) {

    public static DrawResponse from(DrawResult result) {
        return new DrawResponse(result.id(), result.campaignId(), result.outcome(), result.prizeId());
    }
}
