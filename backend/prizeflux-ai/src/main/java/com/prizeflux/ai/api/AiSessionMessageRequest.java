package com.prizeflux.ai.api;

public record AiSessionMessageRequest(long campaignId, String question, long expectedVersion) {
    public AiSessionMessageRequest {
        if (campaignId <= 0 || question == null || question.isBlank() || question.length() > 1000
                || expectedVersion < 0) {
            throw new IllegalArgumentException("invalid session message request");
        }
    }
}
