package com.prizeflux.ai.api;

public record AiCampaignRequest(long campaignId, String question) {
    public AiCampaignRequest {
        if (campaignId <= 0 || question == null || question.isBlank() || question.length() > 1000) {
            throw new IllegalArgumentException("invalid AI campaign request");
        }
    }
}
