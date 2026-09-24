package com.prizeflux.campaign.controller.dto;

import com.prizeflux.campaign.application.DrawHistoryEntry;
import com.prizeflux.campaign.application.DrawOutcome;

import java.time.Instant;

/**
 * 对外只读的历史条目投影：含发奖状态 {@code fulfillmentStatus} 与抽奖时间 {@code drawnAt}。
 * {@code drawId} 即 {@code draw_result.id}，与抽奖响应 {@code DrawResponse.requestId} 同源。
 */
public record DrawHistoryItem(
        long drawId,
        long campaignId,
        DrawOutcome outcome,
        Long prizeId,
        String fulfillmentStatus,
        Instant drawnAt) {

    public static DrawHistoryItem from(DrawHistoryEntry entry) {
        return new DrawHistoryItem(entry.id(), entry.campaignId(), entry.outcome(),
                entry.prizeId(), entry.fulfillmentStatus(), entry.drawnAt());
    }
}
