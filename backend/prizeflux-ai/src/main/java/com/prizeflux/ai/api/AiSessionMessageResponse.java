package com.prizeflux.ai.api;

public record AiSessionMessageResponse(String sessionId, long version, int omittedMessageCount,
                                       AiCampaignResponse result) {
}
