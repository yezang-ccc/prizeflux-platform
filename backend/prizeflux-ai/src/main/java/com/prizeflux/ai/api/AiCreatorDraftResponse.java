package com.prizeflux.ai.api;

import java.util.List;

public record AiCreatorDraftResponse(String answer, boolean valid, List<String> issues,
        boolean simulated, String status, List<AiTraceStep> trace) {
    public AiCreatorDraftResponse {
        issues = List.copyOf(issues);
        trace = List.copyOf(trace);
    }
}
