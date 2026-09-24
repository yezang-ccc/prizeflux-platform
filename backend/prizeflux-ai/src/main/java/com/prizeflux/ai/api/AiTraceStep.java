package com.prizeflux.ai.api;

public record AiTraceStep(String kind, String toolName, Boolean toolSucceeded) {
}
