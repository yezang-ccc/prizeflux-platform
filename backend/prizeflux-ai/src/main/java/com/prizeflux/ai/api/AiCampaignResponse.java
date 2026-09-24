package com.prizeflux.ai.api;

import java.util.List;

public record AiCampaignResponse(String answer, boolean simulated, String status, List<AiTraceStep> trace) {
    public AiCampaignResponse {
        trace = List.copyOf(trace);
    }
}
