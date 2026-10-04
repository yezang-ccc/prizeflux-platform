package com.prizeflux.campaign.controller.dto;

import com.prizeflux.campaign.application.DrawQueueView;
import com.prizeflux.campaign.application.DrawResult;

import java.time.Instant;

public record DrawQueueResponse(String queueRequestId, String requestKey, String status,
                                DrawResponse result, String errorCode,
                                Instant createdAt, Instant completedAt) {
    public static DrawQueueResponse from(DrawQueueView view) {
        DrawResult result = view.result();
        return new DrawQueueResponse(view.queueRequestId(), view.requestKey(), view.status(),
                result == null ? null : DrawResponse.from(result), view.errorCode(),
                view.createdAt(), view.completedAt());
    }
}
