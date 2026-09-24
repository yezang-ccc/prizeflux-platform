package com.prizeflux.ai.api;

public record AiSessionEventView(String kind, String content, String toolName, Boolean toolSucceeded) {
}
